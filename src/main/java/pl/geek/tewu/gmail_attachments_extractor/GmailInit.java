package pl.geek.tewu.gmail_attachments_extractor;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;

import java.io.*;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;


public class GmailInit {
    public static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();


    public static Gmail getGmail(String appName, Path credentialsFilePath, List<String> scopes, Path tokensDirPath) throws IOException, GeneralSecurityException {
        final NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        return new Gmail.Builder(httpTransport, JSON_FACTORY, getCredentials(credentialsFilePath, scopes, tokensDirPath, httpTransport))
                .setApplicationName(appName)
                .build();
    }


    private static Credential getCredentials(Path credentialsFilePath, List<String> scopes, Path tokensDirPath, NetHttpTransport httpTransport) throws IOException {
        // Load client secrets.
        File credentialsFile = credentialsFilePath.toFile();
        if (!credentialsFile.exists())
            throw new FileNotFoundException("File not found: " + credentialsFilePath);
        InputStream in = new FileInputStream(credentialsFile);// GmailInit.class.getResourceAsStream(credentialsFilePath);
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        // Build flow and trigger user authorization request.
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, scopes)
                .setDataStoreFactory(new FileDataStoreFactory(tokensDirPath.toFile()))
                .setAccessType("offline")
                .build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }
}