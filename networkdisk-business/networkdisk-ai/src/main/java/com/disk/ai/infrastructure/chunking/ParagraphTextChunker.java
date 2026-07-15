package com.disk.ai.infrastructure.chunking;

import com.disk.ai.infrastructure.config.AiIndexProperties;
import com.disk.ai.infrastructure.parser.DocumentBlock;
import com.disk.ai.infrastructure.parser.ParsedDocument;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
// 文本切块器。
// AI 向量检索不能直接把整篇长文档作为一个向量，所以要拆成多个较短的 TextChunk。
public class ParagraphTextChunker {

    // 读取 chunk-size 和 chunk-overlap 配置。
    private final AiIndexProperties aiIndexProperties;

    public List<TextChunk> chunk(ParsedDocument parsedDocument) {
        // result 保存最终切出来的所有文本块。
        List<TextChunk> result = new ArrayList<>();
        // 全局 chunk 序号，跨段落递增。
        int globalChunkIndex = 0;
        // 先遍历 TikaDocumentParser 拆出来的段落块。
        // 遍历上一步splitIntoBlocks拆分出来的每一个段落块
        for (DocumentBlock block : parsedDocument.getBlocks()) {
//            去掉的是 Unicode 标准中定义为"空白字符"（Whitespace） 的所有字符
            String blockText = StringUtils.trimToEmpty(block.getText());
            if (StringUtils.isBlank(blockText)) {
                continue;
            }
            // 在当前段落内按 chunkSize 滑动切片。
            int start = 0;
            while (start < blockText.length()) {
                // 1. 计算理论末尾下标：起始位置 + 配置的单块最大长度，不超过段落总长度
                int rawEnd = Math.min(start + aiIndexProperties.getChunkSize(), blockText.length());
                // 2. 优化截断点：向前寻找空白/空格，避免一句话中间硬切断，提升语义完整性
                int end = adjustEnd(blockText, start, rawEnd);
                // 截取当前分片文本，去除首尾空白
                String chunkText = StringUtils.trimToEmpty(blockText.substring(start, end));
                // 只保存有内容的分片，过滤纯空白切片
                if (StringUtils.isNotBlank(chunkText)) {
                    // 记录 chunk 的文本、所属段落、全文位置和粗略 token 估算。
                    TextChunk chunk = new TextChunk();
                    chunk.setChunkIndex(globalChunkIndex++);
                    chunk.setBlockIndex(block.getBlockIndex());
                    chunk.setText(chunkText);
                    chunk.setStartOffset(block.getStartOffset() + start);
                    chunk.setEndOffset(block.getStartOffset() + end);
                    chunk.setTokenEstimate(Math.max(1, (int) Math.ceil(chunkText.length() / 4.0d)));
                    result.add(chunk);
                }
                if (end >= blockText.length()) {
                    break;
                }
                // 滑动窗口：设置下一轮起始位置，实现分片重叠
                // end - 重叠长度，保证前后两块有重复文本；最低不能小于start+1防止死循环
                start = Math.max(end - aiIndexProperties.getChunkOverlap(), start + 1);
            }
        }
        // 兜底逻辑：极端情况段落遍历没生成任何分片，但文档有文本
        // 把整篇文档作为一个完整分片返回，防止空列表报错
//        ：按当前 TikaDocumentParser 的实现，plainText 有内容但 blocks=[] 不会出现；
//        chunker 那个兜底是防未来改代码、外部构造 ParsedDocument、或者 blocks 内容异常。
        if (result.isEmpty() && StringUtils.isNotBlank(parsedDocument.getPlainText())) {
            // 兜底：如果段落切块没有结果，但全文有内容，就把全文作为一个 chunk。
            TextChunk fallback = new TextChunk();
            fallback.setChunkIndex(0);
            fallback.setBlockIndex(0);
            fallback.setText(parsedDocument.getPlainText());
            fallback.setStartOffset(0);
            fallback.setEndOffset(parsedDocument.getPlainText().length());
            fallback.setTokenEstimate(Math.max(1, (int) Math.ceil(parsedDocument.getPlainText().length() / 4.0d)));
            result.add(fallback);
        }
        return result;
    }

    private int adjustEnd(String text, int start, int rawEnd) {
        // rawEnd 已经到末尾时，直接返回全文长度。
        if (rawEnd >= text.length()) {
            return text.length();
        }
        int candidate = -1;
        // 从 rawEnd 往前找空白字符，但最多退到 chunkSize 的一半，避免切得太短。
        for (int i = rawEnd; i > start + (aiIndexProperties.getChunkSize() / 2); i--) {
            // 判断前一个字符是否为空白（空格、换行、制表符）
            if (Character.isWhitespace(text.charAt(i - 1))) {
                candidate = i - 1;
                break;
            }
        }
        // // 找到合法空白断点就用，没找到则使用原始硬截断位置
        return candidate > start ? candidate : rawEnd;
    }
}
