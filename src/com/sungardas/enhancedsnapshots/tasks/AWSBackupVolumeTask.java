package com.sungardas.enhancedsnapshots.tasks;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Snapshot;
import com.amazonaws.services.ec2.model.Volume;
import com.sungardas.enhancedsnapshots.aws.dynamodb.model.BackupEntry;
import com.sungardas.enhancedsnapshots.aws.dynamodb.model.BackupState;
import com.sungardas.enhancedsnapshots.aws.dynamodb.model.TaskEntry;
import com.sungardas.enhancedsnapshots.aws.dynamodb.model.WorkerConfiguration;
import com.sungardas.enhancedsnapshots.aws.dynamodb.repository.BackupRepository;
import com.sungardas.enhancedsnapshots.aws.dynamodb.repository.TaskRepository;
import com.sungardas.enhancedsnapshots.dto.CopyingTaskProgressDto;
import com.sungardas.enhancedsnapshots.exception.EnhancedSnapshotsInterruptedException;
import com.sungardas.enhancedsnapshots.service.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static com.sungardas.enhancedsnapshots.aws.dynamodb.model.TaskEntry.TaskEntryStatus.ERROR;
import static com.sungardas.enhancedsnapshots.aws.dynamodb.model.TaskEntry.TaskEntryStatus.RUNNING;
import static java.lang.String.format;

@Component
@Scope("prototype")
@Profile("prod")
public class AWSBackupVolumeTask implements BackupTask {
    private static final Logger LOG = LogManager
            .getLogger(AWSBackupVolumeTask.class);

    @Value("${sungardas.worker.configuration}")
    private String configurationId;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private StorageService storageService;

    @Autowired
    private BackupRepository backupRepository;


    @Autowired
    private SnapshotService snapshotService;

    @Autowired
    private AWSCommunicationService awsCommunication;
    private TaskEntry taskEntry;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private TaskService taskService;

    private WorkerConfiguration configuration;

    public void setTaskEntry(TaskEntry taskEntry) {
        this.taskEntry = taskEntry;
    }

    public void execute() {
        String volumeId = taskEntry.getVolume();
        try {
            if (Thread.interrupted()) {
                throw new EnhancedSnapshotsInterruptedException("Task interrupted");
            }
            notificationService.notifyAboutTaskProgress(taskEntry.getId(), "Starting backup task", 0);
            configuration = configurationService.getWorkerConfiguration();

            LOG.info(format(
                    "AWSBackupVolumeTask: Starting backup process for volume %s",
                    volumeId));
            LOG.info("Task " + taskEntry.getId()
                    + ": Change task state to 'inprogress'");
            taskEntry.setStatus(RUNNING.getStatus());
            taskRepository.save(taskEntry);

            Volume tempVolume = null;
            String attachedDeviceName = null;

            if (Thread.interrupted()) {
                throw new EnhancedSnapshotsInterruptedException("Task interrupted");
            }
            notificationService.notifyAboutTaskProgress(taskEntry.getId(), "Preparing temp volume", 5);
            tempVolume = createAndAttachBackupVolume(volumeId,
                    configuration.getConfigurationId());
            try {
                TimeUnit.MINUTES.sleep(1);
            } catch (InterruptedException e1) {
                e1.printStackTrace();
            }
            if (Thread.interrupted()) {
                throw new EnhancedSnapshotsInterruptedException("Task interrupted");
            }
            notificationService.notifyAboutTaskProgress(taskEntry.getId(), "Checking volume", 10);
            attachedDeviceName = storageService.detectFsDevName(tempVolume);

            String backupDate = String.valueOf(System.currentTimeMillis());

            Volume volumeToBackup = awsCommunication.getVolume(volumeId);
            String snapshotId = tempVolume.getSnapshotId();
            String volumeType = volumeToBackup.getVolumeType();
            String iops = (volumeToBackup.getIops() != null) ? volumeToBackup
                    .getIops().toString() : "";
            String sizeGib = tempVolume.getSize().toString();

            String backupFileName = volumeId + "." + backupDate + "." + volumeType + "." + iops + ".backup";

            BackupEntry backup = new BackupEntry(volumeId, backupFileName, backupDate, "", BackupState.INPROGRESS,
                    configuration.getConfigurationId(), snapshotId, volumeType, iops, sizeGib);
            if (Thread.interrupted()) {
                throw new EnhancedSnapshotsInterruptedException("Task interrupted");
            }
            notificationService.notifyAboutTaskProgress(taskEntry.getId(), "Copying...", 15);
            boolean backupStatus = false;
            try {
                String source = attachedDeviceName;
                LOG.info("Starting copying: " + source + " to:" + backupFileName);
                CopyingTaskProgressDto dto = new CopyingTaskProgressDto(taskEntry.getId(), 15, 80, Long.parseLong(backup.getSizeGiB()));
                storageService.javaBinaryCopy(source, configuration.getSdfsMountPoint() + backupFileName, dto);
                backupStatus = true;
            } catch (IOException | InterruptedException e) {
                LOG.fatal(format("Backup of volume %s failed", volumeId));
                LOG.fatal(e);
                File brocken = new File(configuration.getSdfsMountPoint() + backupFileName);
                if (brocken.exists()) {
                    if (brocken.delete()) {
                        LOG.info("Broken backup {} was deleted", brocken.getName());
                    } else {
                        LOG.info("Can't delete broken file {}", brocken.getName());
                    }
                }
            }
            if (Thread.interrupted()) {
                throw new EnhancedSnapshotsInterruptedException("Task interrupted");
            }
            notificationService.notifyAboutTaskProgress(taskEntry.getId(), "Detaching temp volume", 80);
            LOG.info("Detaching volume: {}", tempVolume.getVolumeId());
            awsCommunication.detachVolume(tempVolume);
            if (Thread.interrupted()) {
                throw new EnhancedSnapshotsInterruptedException("Task interrupted");
            }
            notificationService.notifyAboutTaskProgress(taskEntry.getId(), "Deleting temp volume", 85);
            LOG.info("Deleting temporary volume: {}", tempVolume.getVolumeId());
            awsCommunication.deleteVolume(tempVolume);
            if (Thread.interrupted()) {
                throw new EnhancedSnapshotsInterruptedException("Task interrupted");
            }
            if (backupStatus) {
                long backupSize = storageService.getSize(configuration.getSdfsMountPoint() + backupFileName);
                long backupCreationtime = storageService.getBackupCreationTime(configuration.getSdfsMountPoint() + backupFileName);
                LOG.info("Backup creation time: {}", backupCreationtime);
                LOG.info("Backup size: {}", backupSize);

                if (Thread.interrupted()) {
                    throw new EnhancedSnapshotsInterruptedException("Task interrupted");
                }
                LOG.info("Put backup entry to the Backup List: {}", backup.toString());
                notificationService.notifyAboutTaskProgress(taskEntry.getId(), "Backup complete", 90);
                backup.setState(BackupState.COMPLETED.getState());
                backup.setSize(String.valueOf(backupSize));
                backupRepository.save(backup);

                LOG.info(format("Backup process for volume %s finished successfully ", volumeId));
                LOG.info("Task " + taskEntry.getId() + ": Delete completed task:" + taskEntry.getId());
                LOG.info("Cleaning up previously created snapshots");
                LOG.info("Storing snapshot data: [{},{},{}]", volumeId, snapshotId, configurationId);

                String previousSnapshot = snapshotService.getSnapshotId(volumeId, configurationId);
                if (previousSnapshot != null) {
                    if (Thread.interrupted()) {
                        throw new EnhancedSnapshotsInterruptedException("Task interrupted");
                    }
                    notificationService.notifyAboutTaskProgress(taskEntry.getId(), "Deleting previous snapshot", 95);
                    LOG.info("Deleting previous snapshot {}", previousSnapshot);
                    awsCommunication.deleteSnapshot(previousSnapshot);
                }

                snapshotService.saveSnapshot(volumeId, configurationId, snapshotId);


                taskService.complete(taskEntry);
                LOG.info("Task completed.");
                if (Thread.interrupted()) {
                    throw new EnhancedSnapshotsInterruptedException("Task interrupted");
                }
                notificationService.notifyAboutTaskProgress(taskEntry.getId(), "Task complete", 100);
                retentionService.apply();
            } else {
                LOG.warn(format("Backup process for volume %s failed ", volumeId));
                taskEntry.setStatus(ERROR.toString());
                taskRepository.save(taskEntry);
            }
        } catch (AmazonClientException e) {
            LOG.error(format("Backup process for volume %s failed ", volumeId));
            LOG.error(e);
            taskEntry.setStatus(ERROR.toString());
            taskRepository.save(taskEntry);
        }
    }

    private Volume createAndAttachBackupVolume(String volumeId,
                                               String instanceId) {
        Instance instance = awsCommunication.getInstance(instanceId);
        if (instance == null) {
            LOG.error("\nCan't get access to " + instanceId + " instance");

        }
        LOG.info("\ninst:" + instance);

        // create snapshot for AMI
        Volume volumeSrc = awsCommunication.getVolume(volumeId);
        if (volumeSrc == null) {
            LOG.error("\nCan't get access to " + volumeId + " volume");

        }

        Snapshot snapshot = awsCommunication
                .waitForCompleteState(awsCommunication
                        .createSnapshot(volumeSrc));
        LOG.info("\nSnapshotEntry created. Check snapshot data:\n"
                + snapshot.toString());

        // create volume
        String instanceAvailabilityZone = instance.getPlacement()
                .getAvailabilityZone();
        Volume volumeDest = awsCommunication
                .waitForAvailableState(awsCommunication
                        .createVolumeFromSnapshot(snapshot,
                                instanceAvailabilityZone));
        LOG.info("\nVolume created. Check volume data:\n"
                + volumeDest.toString());

        // create temporary tag
        awsCommunication.createTemporaryTag(volumeDest.getVolumeId(),volumeSrc.getVolumeId());

        // mount AMI volume
        awsCommunication.attachVolume(instance, volumeDest);
        return awsCommunication.syncVolume(volumeDest);
    }
}
