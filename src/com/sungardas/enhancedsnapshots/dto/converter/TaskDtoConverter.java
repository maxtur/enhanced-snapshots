package com.sungardas.enhancedsnapshots.dto.converter;

import com.sungardas.enhancedsnapshots.aws.dynamodb.model.TaskEntry;
import com.sungardas.enhancedsnapshots.dto.TaskDto;
import org.springframework.beans.BeanUtils;

import java.util.ArrayList;
import java.util.List;


public class TaskDtoConverter {

	public static TaskDto convert(TaskEntry task) {
		TaskDto taskDto = new TaskDto();
		BeanUtils.copyProperties(task, taskDto);
		taskDto.setSchedulerTime(task.getSchedulerTime());
		return taskDto;
	}

	public static TaskEntry convert(TaskDto taskDto) {
		TaskEntry task = new TaskEntry();
		BeanUtils.copyProperties(taskDto, task);
		task.setOptions(taskDto.getBackupFileName());
		return task;
	}

	public static List<TaskDto> convert(Iterable<TaskEntry> taskEntries) {
		List<TaskDto> dtos = new ArrayList<>();
		for (TaskEntry task : taskEntries) {
			dtos.add(convert(task));
		}
		return dtos;
	}
}