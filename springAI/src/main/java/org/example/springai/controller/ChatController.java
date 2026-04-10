package org.example.springai.controller;

import org.example.springai.repository.ChatHistoryRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
public class ChatController {
    @Autowired
    private ChatHistoryRepository chatHistoryRepository;

    @Autowired
    private ChatClient chatClient;
    @RequestMapping(value = "/chat", produces = "text/html;charset=UTF-8")
    public Flux<String> chat(String prompt, String chatId) {
        //保存会话id
        chatHistoryRepository.save("chat", chatId);
        return chatClient
                .prompt()
                .user( prompt)
                .advisors(a->a.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
     }
}
