package com.disk.file.fastdfs;

import com.disk.base.exception.SystemException;
import com.disk.base.utils.FileUtil;
import com.disk.base.utils.UUIDUtil;
import com.disk.file.config.FastDFSStorageEngineConfig;
import com.disk.file.context.DeleteFileContext;
import com.disk.file.context.MergeFileContext;
import com.disk.file.context.ReadFileContext;
import com.disk.file.context.StoreFileChunkContext;
import com.disk.file.context.StoreFileContext;
import com.disk.file.core.AbstractStorageEngine;
import com.disk.lock.DistributeLock;
import com.github.tobato.fastdfs.domain.fdfs.StorePath;
import com.github.tobato.fastdfs.domain.proto.storage.DownloadByteArray;
import com.github.tobato.fastdfs.service.FastFileStorageClient;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * FastDFS storage adapter.
 *
 * <p>The application owns the resumable-upload protocol, while FastDFS owns
 * completed binary files. Chunks are staged locally, verified after merge,
 * then uploaded as one stream to FastDFS. This keeps the existing upload API
 * and lets an interrupted merge be retried from its chunks.</p>
 */
@Component
@ConditionalOnProperty(prefix = "com.disk.file.storage.engine", name = "type", havingValue = "fdfs")
public class FastDFSStorageEngine extends AbstractStorageEngine {

    private static final String MERGED_FILE_CACHE_KEY = "fdfs_merged_file_%s_%s";
    private static final String MERGED_PATH_CACHE_KEY = "fdfs_merged_path_%s";

    private final FastFileStorageClient fastFileStorageClient;
    private final FastDFSStorageEngineConfig config;

    public FastDFSStorageEngine(FastFileStorageClient fastFileStorageClient,
                                FastDFSStorageEngineConfig config) {
        this.fastFileStorageClient = fastFileStorageClient;
        this.config = config;
    }

    @Override
    protected void doStore(StoreFileContext context) throws IOException {
        StorePath storePath = fastFileStorageClient.uploadFile(
                config.getGroup(), context.getInputStream(), context.getTotalSize(),
                FileUtil.getFileExtName(context.getFilename()));
        context.setRealPath(storePath.getFullPath());
    }

    @Override
    protected void doDelete(DeleteFileContext context) throws IOException {
        if (CollectionUtils.isEmpty(context.getRealFilePathList())) {
            return;
        }
        for (String realPath : context.getRealFilePathList()) {
            fastFileStorageClient.deleteFile(realPath);
            evictMergedFileCache(realPath);
        }
    }

    @Override
    protected void doStoreChunk(StoreFileChunkContext context) throws IOException {
        String chunkPath = FileUtil.generateStoreFileChunkRealPath(
                requiredPath(config.getTemporaryChunkPath(), "temporary-chunk-path"),
                context.getIdentifier(), context.getChunkNumber());
        FileUtil.writeStreamToFile(context.getInputStream(), new File(chunkPath), context.getCurrentChunkSize());
        context.setRealPath(chunkPath);
    }

    /**
     * The public entry point is locked because the abstract template method
     * invokes doMergeFile internally and therefore cannot be proxied by AOP.
     */
    @Override
    @DistributeLock(scene = "FASTDFS_FILE_MERGE", keyExpression = "#context.userId + '-' + #context.identifier", expireTime = 60000)
    public void mergeFile(MergeFileContext context) throws IOException {
        super.mergeFile(context);
    }

    @Override
    protected void doMergeFile(MergeFileContext context) throws IOException {
        String mergedFileCacheKey = mergedFileCacheKey(context.getIdentifier(), context.getUserId());
        String existingRealPath = getCache().get(mergedFileCacheKey, String.class);
        if (existingRealPath != null) {
            context.setRealPath(existingRealPath);
            return;
        }

        Path mergedPath = mergeChunksToTemporaryFile(context);
        try (FileInputStream inputStream = new FileInputStream(mergedPath.toFile())) {
            verifyIdentifierWhenMd5(context.getIdentifier(), mergedPath);
            StorePath storePath = fastFileStorageClient.uploadFile(
                    config.getGroup(), inputStream, Files.size(mergedPath),
                    FileUtil.getFileExtName(context.getFilename()));
            String realPath = storePath.getFullPath();
            getCache().put(mergedFileCacheKey, realPath);
            getCache().put(String.format(MERGED_PATH_CACHE_KEY, realPath), mergedFileCacheKey);
            context.setRealPath(realPath);
        } finally {
            // A merge failure retains source chunks for retry; the derived file
            // is disposable and is removed in both success and failure paths.
            Files.deleteIfExists(mergedPath);
        }
    }

    @Override
    public void cleanupTemporaryChunks(List<String> realPathList) throws IOException {
        if (CollectionUtils.isNotEmpty(realPathList)) {
            FileUtil.deleteFiles(realPathList);
        }
    }

    @Override
    protected void doReadFile(ReadFileContext context) throws IOException {
        String realPath = context.getRealPath();
        int groupDelimiter = realPath.indexOf('/');
        if (groupDelimiter <= 0 || groupDelimiter == realPath.length() - 1) {
            throw new SystemException("invalid FastDFS file path");
        }
        String group = realPath.substring(0, groupDelimiter);
        String path = realPath.substring(groupDelimiter + 1);
        try (OutputStream outputStream = context.getOutputStream()) {
            byte[] bytes = fastFileStorageClient.downloadFile(group, path, new DownloadByteArray());
            outputStream.write(bytes);
            outputStream.flush();
        }
    }

    private Path mergeChunksToTemporaryFile(MergeFileContext context) throws IOException {
        Path mergeDirectory = Path.of(requiredPath(config.getTemporaryMergePath(), "temporary-merge-path"));
        Files.createDirectories(mergeDirectory);
        Path mergedPath = mergeDirectory.resolve(UUIDUtil.getUUID() + FileUtil.getFileSuffix(context.getFilename()));
        List<String> chunkPaths = new ArrayList<>(context.getRealPathList());
        try (OutputStream outputStream = Files.newOutputStream(mergedPath,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (String chunkPath : chunkPaths) {
                Files.copy(Path.of(chunkPath), outputStream);
            }
        } catch (IOException exception) {
            Files.deleteIfExists(mergedPath);
            throw exception;
        }
        return mergedPath;
    }

    private void verifyIdentifierWhenMd5(String identifier, Path mergedPath) throws IOException {
        if (identifier == null || !identifier.matches("(?i)[0-9a-f]{32}")) {
            return;
        }
        try (FileInputStream inputStream = new FileInputStream(mergedPath.toFile())) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            String actual = java.util.HexFormat.of().formatHex(digest.digest());
            if (!identifier.equalsIgnoreCase(actual)) {
                throw new SystemException("merged file checksum does not match identifier");
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("MD5 algorithm is unavailable", exception);
        }
    }

    private void evictMergedFileCache(String realPath) {
        String pathCacheKey = String.format(MERGED_PATH_CACHE_KEY, realPath);
        String mergedFileCacheKey = getCache().get(pathCacheKey, String.class);
        getCache().evict(pathCacheKey);
        if (mergedFileCacheKey != null) {
            getCache().evict(mergedFileCacheKey);
        }
    }

    private String mergedFileCacheKey(String identifier, Long userId) {
        return String.format(MERGED_FILE_CACHE_KEY, userId, identifier);
    }

    private String requiredPath(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new SystemException("FastDFS " + propertyName + " must be configured");
        }
        return value;
    }
}
