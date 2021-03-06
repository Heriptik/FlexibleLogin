/*
 * This file is part of FlexibleLogin
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015-2018 contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.github.games647.flexiblelogin.command;

import com.github.games647.flexiblelogin.FlexibleLogin;
import com.github.games647.flexiblelogin.config.node.MailConfig;
import com.github.games647.flexiblelogin.config.Settings;
import com.github.games647.flexiblelogin.storage.Account;
import com.github.games647.flexiblelogin.tasks.SendMailTask;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;

import java.io.UnsupportedEncodingException;
import java.util.Calendar;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;

import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.NoSuchProviderException;
import javax.mail.Provider;
import javax.mail.Provider.Type;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.apache.commons.lang3.RandomStringUtils;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.scheduler.Task;

public class ForgotPasswordCommand extends AbstractCommand {

    private static final int PASSWORD_LENGTH = 16;

    private final Supplier<String> passwordSupplier = () -> RandomStringUtils.randomAlphanumeric(PASSWORD_LENGTH);

    @Inject
    ForgotPasswordCommand(FlexibleLogin plugin, Logger logger, Settings settings) {
        super(plugin, logger, settings);
    }

    @Override
    public CommandResult execute(CommandSource src, CommandContext args) throws CommandException {
        if (!(src instanceof Player)) {
            throw new CommandException(settings.getText().getPlayersOnly());
        }

        if (!settings.getGeneral().getMail().isEnabled()) {
            throw new CommandException(settings.getText().getMailNotEnabled());
        }

        Player player = (Player) src;
        Optional<Account> optAccount = plugin.getDatabase().getAccount(player);
        if (optAccount.isPresent()) {
            if (optAccount.get().isLoggedIn()) {
                throw new CommandException(settings.getText().getAlreadyLoggedIn());
            }
        } else {
            throw new CommandException(settings.getText().getAccountNotLoaded());
        }

        Account account = optAccount.get();

        Optional<String> optEmail = account.getMail();
        if (!optEmail.isPresent()) {
            throw new CommandException(settings.getText().getUncommittedMailAddress());
        }

        prepareSend(player, account, optEmail.get());
        return CommandResult.success();
    }

    private void prepareSend(Player player, Account account, String email) {
        String newPassword = passwordSupplier.get();

        MailConfig emailConfig = settings.getGeneral().getMail();
        Session session = buildSession(emailConfig);

        try {
            Message message = buildMessage(player, email, newPassword, emailConfig, session);

            //send email
            Task.builder()
                    .async()
                    .execute(new SendMailTask(plugin, player, session, message))
                    .submit(plugin);

            //set new password here if the email sending fails fails we have still the old password
            account.setPasswordHash(plugin.getHasher().hash(newPassword));
            Task.builder()
                    .async()
                    .execute(() -> plugin.getDatabase().save(account))
                    .submit(plugin);
        } catch (Exception ex) {
            logger.error("Error executing command", ex);
            player.sendMessage(settings.getText().getErrorExecutingCommand());
        }
    }

    private Session buildSession(MailConfig emailConfig) {
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", emailConfig.getHost());
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.smtp.port", String.valueOf(emailConfig.getPort()));

        //ssl
        properties.setProperty("mail.smtp.socketFactory.port", String.valueOf(emailConfig.getPort()));
        properties.setProperty("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        properties.setProperty("mail.smtp.socketFactory.fallback", "false");
        properties.setProperty("mail.smtp.starttls.enable", String.valueOf(true));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");

        //we only need to send the message so we use smtps
        properties.setProperty("mail.transport.protocol", "smtps");

        //explicit override stmp provider because of issues with relocation
        Session session = Session.getDefaultInstance(properties);
        try {
            session.setProvider(new Provider(Type.TRANSPORT, "smtps",
                    "flexiblelogin.mail.smtp.SMTPSSLTransport", "Oracle", "1.6.0"));
        } catch (NoSuchProviderException noSuchProvider) {
            logger.error("Failed to add SMTP provider", noSuchProvider);
        }

        return session;
    }

    private MimeMessage buildMessage(User player, String email, String newPassword, MailConfig emailConfig,
                                     Session session) throws MessagingException, UnsupportedEncodingException {
        String serverName = Sponge.getServer().getBoundAddress()
                .map(sa -> sa.getAddress().getHostAddress())
                .orElse("Minecraft Server");
        ImmutableMap<String, String> variables = ImmutableMap.of("player", player.getName(),
                "server", serverName,
                "password", newPassword);

        MimeMessage message = new MimeMessage(session);
        String senderEmail = emailConfig.getAccount();

        //sender email with an alias
        message.setFrom(new InternetAddress(senderEmail, emailConfig.getSenderName()));
        message.setRecipient(RecipientType.TO, new InternetAddress(email, player.getName()));
        message.setSubject(emailConfig.getSubject(serverName, player.getName()).toPlain());

        //current time
        message.setSentDate(Calendar.getInstance().getTime());
        String textContent = emailConfig.getText(serverName, player.getName(), newPassword).toPlain();

        //html part
        BodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(textContent, "text/html; charset=UTF-8");

        //plain text
        BodyPart textPart = new MimeBodyPart();
        textPart.setContent(textContent.replaceAll("(?s)<[^>]*>(\\s*<[^>]*>)*", " "), "text/plain; charset=UTF-8");

        Multipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(htmlPart);
        alternative.addBodyPart(textPart);
        message.setContent(alternative);
        return message;
    }

    @Override
    public CommandSpec buildSpec(Settings settings) {
        return buildPlayerCommand(settings, "forgot")
                .executor(this)
                .build();
    }
}
