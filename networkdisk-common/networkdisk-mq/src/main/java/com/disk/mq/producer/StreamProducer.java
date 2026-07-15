package com.disk.mq.producer;

import com.alibaba.fastjson.JSON;
import com.disk.mq.param.MessageBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;

import java.util.UUID;

/**
 * SpringCloud Stream 通用消息生产者工具类
 * 底层封装StreamBridge统一发送RocketMQ消息，统一包装外层MessageBody载体
 * 能力：支持指定TAG、自定义消息头，统一日志埋点
 */
public class StreamProducer {

    private static Logger logger = LoggerFactory.getLogger(StreamProducer.class);

    // Stream核心发送桥梁，通过配置文件binding映射topic
    @Autowired
    private StreamBridge streamBridge;

    /**
     * 基础发送方法：仅携带TAG，无自定义header
     * @param bingingName 输出绑定通道名称（xxx-out-0）
     * @param tag RocketMQ消息标签，消费者可根据tag过滤消息
     * @param msg 业务JSON字符串（真实业务载荷）
     * @return true=消息成功投递Broker；false=发送失败
     */
    public boolean send(String bingingName, String tag, String msg) {
        // 统一外层包装MessageBody载体
        MessageBody message = new MessageBody()
                .setIdentifier(UUID.randomUUID().toString()) // 全局唯一消息流水ID，链路追踪
                .setBody(msg); // 存放真实业务JSON字符串
        logger.info("send message : {} , {}", bingingName, JSON.toJSONString(message));

        // Spring标准消息构建器
        // withPayload：外层统一载体MessageBody
        // setHeader("TAGS", tag)：RocketMQ识别TAG头，用于消息过滤
        boolean result = streamBridge.send(
                bingingName,
                MessageBuilder.withPayload(message)
                        .setHeader("TAGS", tag)
                        .build()
        );
        logger.info("send result : {} , {}", bingingName, result);
        return result;
    }

    /**
     * 扩展发送方法：支持自定义额外消息Header
     * @param bingingName 输出通道绑定名
     * @param tag 消息TAG
     * @param msg 业务JSON载荷
     * @param headerKey 自定义header键
     * @param headerValue 自定义header值
     * @return 投递结果
     */
    public boolean send(String bingingName, String tag, String msg, String headerKey, String headerValue) {
        MessageBody message = new MessageBody()
                .setIdentifier(UUID.randomUUID().toString())
                .setBody(msg);
        logger.info("send message : {} , {}", bingingName, JSON.toJSONString(message));

        // 在TAG基础上追加自定义业务消息头
        boolean result = streamBridge.send(
                bingingName,
                MessageBuilder.withPayload(message)
                        .setHeader("TAGS", tag)
                        .setHeader(headerKey, headerValue)
                        .build()
        );
        logger.info("send result : {} , {}", bingingName, result);
        return result;
    }
}
