package com.yesmadam.schedulerservice.common.mail;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.mail.internet.MimeMessage;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends HTML alert mail. Shared across jobs — nothing job-specific belongs here.
 *
 * <p>Exceptions are <b>not</b> swallowed: a send failure propagates so the calling Temporal
 * activity fails and gets retried. Logging-and-continuing would let an alert silently disappear,
 * which defeats the point of alerting.
 */
@Slf4j
@Service
public class MailService {

  private final JavaMailSender mailSender;

  public MailService(JavaMailSender mailSender) {
    this.mailSender = mailSender;
  }

  /**
   * Sends one HTML mail.
   *
   * @param from sender address — must be an SES-verified identity, or SES rejects the message
   * @param to recipients
   * @param subject subject line
   * @param htmlBody body, rendered as HTML
   */
  public void sendHtml(String from, List<String> to, String subject, String htmlBody) {
    if (to == null || to.isEmpty()) {
      log.warn("event=mail_skipped reason=no_recipients subject={}", subject);
      return;
    }

    try {
      MimeMessage message = mailSender.createMimeMessage();
      MimeMessageHelper helper =
          new MimeMessageHelper(
              message, MimeMessageHelper.MULTIPART_MODE_NO, StandardCharsets.UTF_8.name());
      helper.setFrom(from);
      helper.setTo(to.toArray(new String[0]));
      helper.setSubject(subject);
      helper.setText(htmlBody, true);
      mailSender.send(message);

      log.info("event=mail_sent to={} subject={}", to, subject);

    } catch (Exception e) {
      log.error("event=mail_failed to={} subject={} error={}", to, subject, e.getMessage());
      throw new IllegalStateException("Failed to send mail: " + subject, e);
    }
  }
}
