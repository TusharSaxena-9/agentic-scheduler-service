package com.yesmadam.schedulerservice.config;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.sqs.AmazonSQSAsync;
import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * SQS client for the wallet-expiry reminder's enqueue job. Static credentials, not the SDK's
 * default provider chain — the values come from {@code AWS_ACCESS_KEY_ID}/{@code
 * AWS_SECRET_ACCESS_KEY}/{@code AWS_REGION} env vars via placeholders in {@code
 * application.properties}.
 */
@Configuration
public class AwsSqsConfig {

  @Value("${cloud.aws.credentials.access-key}")
  private String accessKey;

  @Value("${cloud.aws.credentials.secret-key}")
  private String secretKey;

  @Value("${cloud.aws.region.static}")
  private String region;

  @Bean
  @Primary
  public AmazonSQSAsync amazonSQSAsync() {
    BasicAWSCredentials awsCreds = new BasicAWSCredentials(accessKey, secretKey);
    return AmazonSQSAsyncClientBuilder.standard()
        .withRegion(region)
        .withCredentials(new AWSStaticCredentialsProvider(awsCreds))
        .build();
  }
}
