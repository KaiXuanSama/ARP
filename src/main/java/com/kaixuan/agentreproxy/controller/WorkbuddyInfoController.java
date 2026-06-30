package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.model.WorkbuddyDesktopInfo;
import com.kaixuan.agentreproxy.service.WorkbuddyInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
public class WorkbuddyInfoController {

    private final WorkbuddyInfoService workbuddyInfoService;

    public WorkbuddyInfoController(WorkbuddyInfoService workbuddyInfoService) {
        this.workbuddyInfoService = workbuddyInfoService;
    }

    @GetMapping("/workbuddy-info")
    public Mono<WorkbuddyDesktopInfo> getWorkbuddyInfo() {
        return Mono.fromCallable(workbuddyInfoService::readInfo);
    }
}
