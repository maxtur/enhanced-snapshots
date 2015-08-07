package com.sungardas.snapdirector.worker;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.sungardas.snapdirector.aws.EnvironmentBasedCredentialsProvider;

public class Main {
	public static final Log LOG = LogFactory.getLog(Main.class);
	public static void main(String[] args) {
		AWSCredentialsProvider awsCredentialsProvider =  new EnvironmentBasedCredentialsProvider();
		String instanceId = retrieveInstanceId();
		
		DynamoDB dynamoDB = new DynamoDB(new AmazonDynamoDBClient(awsCredentialsProvider));
		Table workerConfigurationTable = dynamoDB.getTable("WorkerConfiguration");
		Item configuration = workerConfigurationTable.getItem("workerId", instanceId);
		LOG.info("Loaded Worker Configuration: " + configuration.toJSON()+"\n");
		checkFakeBackupSourceUsage(configuration);
		
		
		ExecutorService  executor = Executors.newSingleThreadExecutor();
		executor.execute(new AWSTaskWorker(awsCredentialsProvider,configuration, instanceId));
		
	}
	
	private static String retrieveInstanceId() {
		String instanceId = null;
		try {
			URL url = new URL("http://169.254.169.254/latest/meta-data/instance-id");
			URLConnection conn = url.openConnection();
			Scanner s = new Scanner(conn.getInputStream());
			if (s.hasNext()) {
				instanceId = s.next();
				LOG.info("Getting Worker InstanceId from metadata: " + instanceId);
				
			}
		} catch (IOException e) {
			LOG.info("Can't get InstanceId from metadata.");
			System.exit(0);
		}
		return instanceId;

	}
	
	private static void checkFakeBackupSourceUsage(Item configuration) {
		String fakeBackupSource;	
		if ( configuration.getBoolean("useFakeBackup") &&
				((fakeBackupSource= configuration.getString("fakeBackupSource"))!=null)) {
				if(new File(fakeBackupSource).exists()) {
					LOG.info("Fake backup source will be used to test flow");
				} else {
					LOG.info("Can't find fake backup file.");
					System.exit(0);
				}
				
			}
		return;
	}
	

}