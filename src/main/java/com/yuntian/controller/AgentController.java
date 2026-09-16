package com.yuntian.controller;


import com.yuntian.dto.AgentChatRequest;
import com.yuntian.dto.AgentChatResponse;
import com.yuntian.service.AgentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    @Autowired
    private AgentService agentService;

    @PostMapping("/chat")
    public AgentChatResponse chat(@RequestBody AgentChatRequest request) {

        return agentService.chat(request);
    }
}
