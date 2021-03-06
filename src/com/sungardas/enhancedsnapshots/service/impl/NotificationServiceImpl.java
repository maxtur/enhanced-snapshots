package com.sungardas.enhancedsnapshots.service.impl;

import com.sungardas.enhancedsnapshots.dto.Dto;
import com.sungardas.enhancedsnapshots.dto.ExceptionDto;
import com.sungardas.enhancedsnapshots.dto.TaskProgressDto;
import com.sungardas.enhancedsnapshots.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationServiceImpl implements NotificationService {

    public static final String TASK_PROGRESS_DESTINATION = "/task";
    public static final String ERROR_DESTINATION = "/error";

    @Autowired
    private SimpMessagingTemplate template;

    @Override
    public void notifyAboutTaskProgress(String taskId, String message, double progress) {
        notifyAboutTaskProgress(new TaskProgressDto(taskId, message, progress));
    }

    @Override
    public void notifyAboutTaskProgress(TaskProgressDto dto) {
        notifyUser(TASK_PROGRESS_DESTINATION, dto);
    }

    @Override
    public void notifyAboutError(ExceptionDto exceptionDto) {
        notifyUser(ERROR_DESTINATION, exceptionDto);
    }

    @Override
    public void notifyUser(String destination, Dto dto) {
        try {
            template.convertAndSend(destination, dto);
        } catch (Throwable t) {

        }
    }
}
