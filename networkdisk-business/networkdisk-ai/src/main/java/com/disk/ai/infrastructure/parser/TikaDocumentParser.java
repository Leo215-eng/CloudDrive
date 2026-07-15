package com.disk.ai.infrastructure.parser;

import com.disk.ai.exception.AiErrorCode;
import com.disk.ai.exception.AiException;
import com.disk.ai.infrastructure.config.AiIndexProperties;
import com.disk.ai.infrastructure.file.AiSourceFile;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
// 文档解析器：用 Apache Tika 自动识别 PDF/Word/PPT/TXT 等文件格式，并提取纯文本。
public class TikaDocumentParser {

    // 读取支持的文件后缀、最大文本长度等索引配置。
    private final AiIndexProperties aiIndexProperties;

    public ParsedDocument parse(AiSourceFile sourceFile) {
        // 1. 前置校验：判断当前文件后缀是否在系统支持解析的白名单内，不支持直接抛异常阻断
        validateSupportedFile(sourceFile.getFileSuffix());
        // Tika原生元数据对象，用于存放解析出的文件属性（文件类型、作者、创建时间等）
        Metadata metadata = new Metadata();
        // sourceFile.getBytes() 获取文件完整二进制字节数组；Tika解析需要InputStream流作为入参
        // try-with-resources语法：实现AutoCloseable的流会在代码块结束后自动关闭，无需手动close，防止IO泄漏
        try (InputStream inputStream = new ByteArrayInputStream(sourceFile.getBytes())) {
        // Tika自动识别解析器：根据文件二进制头、后缀自动匹配PDF/Word/Excel/图片OCR等对应解析器
            AutoDetectParser parser = new AutoDetectParser();
        // BodyContentHandler接收Tika提取出的纯文本；参数-1代表关闭Tika内置文本长度截断，完整读取全文
            // 文本长度限制统一交给业务配置自行控制，灵活性更高
            BodyContentHandler handler = new BodyContentHandler(-1);
            // Tika标准四参数解析方法：输入流、文本接收处理器、元数据存储对象、解析上下文
            // ParseContext用于承载解析配置，无特殊定制需求直接new空对象即可
            parser.parse(inputStream, handler, metadata, new ParseContext());

// 文本归一化清洗：统一换行符、清除多余空行、过滤不可见控制字符，输出规整纯文本
            String plainText = normalize(handler.toString());//获取提取完成的全文纯文本
            // 判断解析文本是否超出配置的最大允许字符长度，超大文本做截断，避免OOM、大模型请求超长报错
            if (plainText.length() > aiIndexProperties.getMaxTextChars()) {
                // 限制最大解析文本长度，避免过大文件拖垮后续切块/模型请求。
                plainText = plainText.substring(0, aiIndexProperties.getMaxTextChars());
            }
            // 校验解析后文本是否全空白/无内容，无有效文字抛出文档空异常
            if (StringUtils.isBlank(plainText)) {
                throw new AiException(AiErrorCode.DOCUMENT_EMPTY);
            }

        // 组装统一解析结果实体
            ParsedDocument parsedDocument = new ParsedDocument();
            // 优先取Tika识别出的文件MIME类型，为空则降级使用源文件传入的ContentType兜底
            parsedDocument.setMediaType(StringUtils.defaultIfBlank(metadata.get(Metadata.CONTENT_TYPE), sourceFile.getContentType()));
            // 记录本次使用的Tika解析器名称，用于日志排查问题
            parsedDocument.setParser(parser.getClass().getSimpleName());
            // 设置清洗、截断后的完整纯文本
            parsedDocument.setPlainText(plainText);
            // 将全文按换行/空行切分为段落分块，用于后续RAG向量分片、文本切块
            parsedDocument.setBlocks(splitIntoBlocks(plainText));
            // 将Tika原生松散Metadata键值对象转换为业务自定义元数据实体，方便序列化入库
            parsedDocument.setMetadata(convertMetadata(metadata));
            return parsedDocument;
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to parse document with Apache Tika", e, AiErrorCode.DOCUMENT_PARSE_FAILED);
        }
    }

    private void validateSupportedFile(String fileSuffix) {
        // 后缀统一小写并去空格，和配置里的 supportedFileSuffixes 做匹配。
        String normalizedSuffix = StringUtils.lowerCase(StringUtils.trimToEmpty(fileSuffix), Locale.ROOT);
        if (!aiIndexProperties.getSupportedFileSuffixes().contains(normalizedSuffix)) {
            throw new AiException(AiErrorCode.UNSUPPORTED_FILE_TYPE);
        }
    }

    private String normalize(String plainText) {
        // 清理空字符、统一换行，把连续 3 个以上空行压成 2 个。
        String normalized = StringUtils.defaultString(plainText)
                .replace("\u0000", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized.trim();
    }

    /**
     * 将清洗后的完整文本按连续空行切分为段落块，记录每段在全文的偏移下标，用于后续分片与原文溯源
     * @param plainText 归一化后的完整文档纯文本
     * @return 段落块列表，包含每段文本、起止偏移、序号、块类型
     */
    private List<DocumentBlock> splitIntoBlocks(String plainText) {
        // 注释：先按空行拆成段落块，后续 chunker 再把长段落切成固定大小 chunk。
        List<DocumentBlock> blocks = new ArrayList<>();
        // 正则分割：匹配2个及以上连续换行+空白字符，以此分割不同段落
        // (?:\\n\\s*){2,}
        // ?: 非捕获分组，只做匹配不单独提取分组内容；\\n换行，\\s任意空白；{2,}至少连续出现两次
        String[] parts = plainText.split("(?:\\n\\s*){2,}");
        // 游标：记录上一段结束位置，用于精准查找当前段落的起始偏移
        int cursor = 0;
        // 段落自增序号
        int blockIndex = 0;

        // 遍历分割后的每一段原始文本
        for (String part : parts) {
            // 去除段落首尾空白，空字符串统一转为空
            String text = StringUtils.trimToEmpty(part);
            // 过滤全空白无效段落
            if (StringUtils.isBlank(text)) {
                continue;
            }

            // start/end 记录这段文字在全文中的位置，方便以后做引用定位。
            // 从cursor游标位置开始查找当前段落文本的起始下标，避免重复匹配前面重复文字
//            第一个参数 text：要查找的子串
//            第二个参数 cursor：从字符串哪个下标开始往后搜
            int start = plainText.indexOf(text, cursor);
            // 查找失败兜底：使用当前游标作为起始位置
            if (start < 0) {
                start = cursor;
            }
            // 计算段落文本在全文的结束偏移
            int end = start + text.length();

            // 组装段落块实体
            DocumentBlock block = new DocumentBlock();
            block.setBlockIndex(blockIndex++); // 段落序号自增
            block.setBlockType("paragraph");    // 块类型标记为段落
            block.setText(text);                // 当前段落干净文本
            block.setStartOffset(start);        // 文本在全文起始偏移量
            block.setEndOffset(end);            // 文本在全文结束偏移量
            blocks.add(block);
            // 更新游标为本段末尾，下一段从该位置开始检索
            cursor = end;
        }

        // 极端情况下没有拆出段落（全文无空行分隔），就把全文作为一个整体block。
        if (blocks.isEmpty()) {
            DocumentBlock block = new DocumentBlock();
            block.setBlockIndex(0);
            block.setBlockType("document"); // 整块文档标记
            block.setText(plainText);
            block.setStartOffset(0);                        // 全文起始0
            block.setEndOffset(plainText.length());         // 全文末尾长度
            blocks.add(block);
        }
        return blocks;
    }

    /**
     * 将Tika原生Metadata元数据对象转为有序字符串Map，过滤空值，方便序列化、日志打印、入库
     * @param metadata Tika解析出来的原生文件元数据
     * @return 有序键值Map，key元数据名称，value对应元数据内容
     */
    private Map<String, String> convertMetadata(Metadata metadata) {
        // Tika 的 Metadata 转成普通 Map，方便后续查看解析信息。
        // LinkedHashMap 保留元数据插入顺序，输出顺序稳定
        Map<String, String> result = new LinkedHashMap<>();
        // 遍历所有元数据key名称
        for (String name : metadata.names()) {
            // 根据key读取对应元数据值
            String value = metadata.get(name);
            // 只保存非空白的有效元数据，过滤空值减少无用数据
            if (StringUtils.isNotBlank(value)) {
                result.put(name, value);
            }
        }
        return result;
    }

}
