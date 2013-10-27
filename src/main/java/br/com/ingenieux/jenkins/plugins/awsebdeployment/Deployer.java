package br.com.ingenieux.jenkins.plugins.awsebdeployment;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

import br.com.ingenieux.jenkins.plugins.awsebdeployment.AWSEBDeploymentBuilder.DescriptorImpl;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSCredentialsProviderChain;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.internal.StaticCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient;
import com.amazonaws.services.elasticbeanstalk.model.CreateApplicationVersionRequest;
import com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentsRequest;
import com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentsResult;
import com.amazonaws.services.elasticbeanstalk.model.S3Location;
import com.amazonaws.services.elasticbeanstalk.model.UpdateEnvironmentRequest;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;

public class Deployer {
	private static final int MAX_ATTEMPTS = 15;

	private AmazonS3 s3;

	private AWSElasticBeanstalkClient awseb;

	private String applicationName;

	private String bucketName;

	private String environmentName;

	private String keyPrefix;

	private String rootDirectory;

	private String versionLabelFormat;

	public Deployer(DescriptorImpl descriptorImpl) {
		AWSCredentialsProvider credentials = new AWSCredentialsProviderChain(
				new StaticCredentialsProvider(new BasicAWSCredentials(
						descriptorImpl.getAwsAccessKeyId(),
						descriptorImpl.getAwsSecretSharedKey())));
		Region region = Region.getRegion(Regions.fromName(descriptorImpl
				.getAwsRegion()));
		ClientConfiguration clientConfig = new ClientConfiguration();

		clientConfig.setUserAgent("ingenieux CloudButler/version");

		this.s3 = region.createClient(AmazonS3Client.class, credentials,
				clientConfig);
		this.awseb = region.createClient(AWSElasticBeanstalkClient.class,
				credentials, clientConfig);

		this.applicationName = descriptorImpl.getApplicationName();
		this.bucketName = descriptorImpl.getBucketName();
		this.environmentName = descriptorImpl.getEnvironmentName();
		this.keyPrefix = descriptorImpl.getKeyPrefix();
		this.rootDirectory = descriptorImpl.getRootDirectory();
		this.versionLabelFormat = descriptorImpl.getVersionLabelFormat();
	}

	public void perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws Exception {
		PrintStream w = listener.getLogger();
		EnvVars env = build.getEnvironment(listener);
		FilePath rootFileObject = new FilePath(build.getWorkspace(), strip(this.rootDirectory));

		File tmpFile = File.createTempFile("awseb-", ".zip");
		FileOutputStream fos = new FileOutputStream(tmpFile);

		if (rootFileObject.isDirectory()) {
			w.println(String.format("Zipping contents of %s into temp file %s",
					rootFileObject, tmpFile));

			rootFileObject.zip(fos);

		} else {
			w.println(String.format("Copying contents of %s into temp file %s", rootFileObject, tmpFile));
			
			rootFileObject.copyTo(fos);
		}
		
		IOUtils.closeQuietly(fos);

		/**
		 * Sets Vars
		 */
		keyPrefix = strip(Util.replaceMacro(keyPrefix, env));
		bucketName = strip(Util.replaceMacro(bucketName, env));
		applicationName = strip(Util.replaceMacro(applicationName, env));
		String versionLabel = strip(Util.replaceMacro(versionLabelFormat, env));

		String objectKey = String.format("%s/%s-%s.zip", keyPrefix,
				applicationName, versionLabel);

		String s3ObjectPath = String.format("s3://%s/%s", bucketName,
				objectKey);

		{
			w.println(String.format("Uploading file %s as %s", tmpFile,
					s3ObjectPath));

			s3.putObject(bucketName, objectKey, tmpFile);
		}

		{
			w.println(String
					.format("Creating application version %s for application %s for path %s",
							versionLabel, applicationName, s3ObjectPath));

			CreateApplicationVersionRequest cavRequest = new CreateApplicationVersionRequest()
					.withApplicationName(applicationName)
					.withAutoCreateApplication(true)
					.withSourceBundle(new S3Location(bucketName, objectKey))
					.withVersionLabel(versionLabel);
			awseb.createApplicationVersion(cavRequest);
		}

		{
			DescribeEnvironmentsResult environments = awseb
					.describeEnvironments(new DescribeEnvironmentsRequest()
							.withApplicationName(applicationName)
							.withEnvironmentNames(environmentName));

			boolean found = (1 == environments.getEnvironments().size());

			if (found) {
				for (int nAttempt = 1; nAttempt <= MAX_ATTEMPTS; nAttempt++) {

					String environmentId = environments.getEnvironments()
							.get(0).getEnvironmentId();

					w.println(String.format("Attempt %d/%s", nAttempt, MAX_ATTEMPTS));
					
					w.println(String
							.format("Environment found (environment id=%s). Attempting to update environment to version label %s",
									environmentId, versionLabel));

					UpdateEnvironmentRequest uavReq = new UpdateEnvironmentRequest()
							.withEnvironmentName(environmentName)
							.withVersionLabel(versionLabel);

					try {
						awseb.updateEnvironment(uavReq);
						
						w.println("q'Apla!");
						
						listener.finished(Result.SUCCESS);
						
						return;
					} catch (Exception exc) {
						w.println("Problem: " + exc.getMessage());
						
						if (nAttempt == MAX_ATTEMPTS) {
							w.println("Giving it up");
							
							listener.fatalError(exc.getMessage());
							listener.finished(Result.FAILURE);
							
							throw exc;
						}
						
						w.println(String.format("Reattempting in 90s, up to %d", MAX_ATTEMPTS));
						
						Thread.sleep(TimeUnit.SECONDS.toMillis(90));
					}
				}
			} else {
				w.println("Environment not found. Continuing");
				listener.finished(Result.SUCCESS);
			}
		}

	}

	private String strip(String str) {
		return StringUtils.strip(str, "/ ");
	}

}
