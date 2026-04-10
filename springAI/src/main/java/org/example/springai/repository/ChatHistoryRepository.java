package org.example.springai.repository;

import java.util.List;

public interface ChatHistoryRepository {
    //保存会话记录
    void save(String type, String chatId);

    //根据类型获取会话id列表
    List<String> getChatIds(String type);
}
