package org.garmash.jpress;

import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyCodeSource;
import groovy.lang.GroovyObject;
import groovy.util.ConfigObject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.util.FileUtils;
import org.jets3t.service.S3Service;
import org.jets3t.service.ServiceException;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.model.StorageObject;
import org.jets3t.service.multi.SimpleThreadedStorageService;
import org.jets3t.service.security.AWSCredentials;
import org.json.JSONException;
import org.json.JSONObject;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Max Garmash
 */
public class WebHook extends HttpServlet {

    public static final String PROCESSED_DIR = "processed";
    public static final String GENERATOR_FILE = "Generator.groovy";

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        final String repoURL = extractRepoURL(req);
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (repoURL.matches(Config.getInstance().get("filter.repo"))) {
                    try {
                        Path baseDir = Files.createTempDirectory("clone");
                        cloneSources(repoURL + ".git", baseDir);
                        GroovyObject generator = initGenerator(baseDir);
                        processSources(generator);
                        uploadToS3(baseDir, isRegenerationEnabled(generator));
                        deleteDirectory(baseDir);
                    }
                    catch (IOException | IllegalAccessException | InstantiationException | GitAPIException | ServiceException e) {
                        e.printStackTrace();
                    }
                }

            }
        }).start();
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    private void deleteDirectory(Path baseDir) {
        try {
            Files.walkFileTree(baseDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc == null) {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    } else {
                        throw exc;
                    }
                }

            });
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean isRegenerationEnabled(GroovyObject generator) {
        return (boolean) ((ConfigObject) ((ConfigObject) generator.getProperty("cfg")).get("site")).get("regenerate");
    }

    private GroovyObject initGenerator(Path baseDir) throws IllegalAccessException, InstantiationException {
        GroovyClassLoader gcl = new GroovyClassLoader();
        InputStream generatorFile = getClass().getClassLoader().getResourceAsStream(GENERATOR_FILE);
        InputStreamReader in = new InputStreamReader(generatorFile);
        BufferedReader reader = new BufferedReader(in);
        GroovyCodeSource codeSource = new GroovyCodeSource(reader, GENERATOR_FILE, "");
        GroovyObject generator = (GroovyObject) gcl.parseClass(codeSource).newInstance();
        generator.invokeMethod("init", new Object[]{baseDir.toAbsolutePath().toString(), baseDir.toAbsolutePath().toString() + "/" + PROCESSED_DIR});
        return generator;
    }

    private void processSources(GroovyObject generator) {
        generator.invokeMethod("process", null);
    }

    private String extractRepoURL(HttpServletRequest req) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(req.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line = null;
        String url = null;
        while ((line = in.readLine()) != null) {
            sb.append(line);
        }
        try {
            String payload = URLDecoder.decode(sb.replace(0, 8, "").toString(), "UTF-8");
            JSONObject json = new JSONObject(payload);
            JSONObject repository = json.getJSONObject("repository");
            url = repository.getString("url");
        }
        catch (JSONException e) {
            e.printStackTrace();
        }
        return url;
    }

    private void cloneSources(String url, Path baseDir) throws GitAPIException {
        Git.cloneRepository().setURI(url).setDirectory(baseDir.toFile()).call();
    }

    private void uploadToS3(Path baseDir, boolean clean) throws ServiceException, IOException {
        String awsAccessKey = Config.getInstance().get("aws.key");
        String awsSecretKey = Config.getInstance().get("aws.secret");
        AWSCredentials awsCredentials = new AWSCredentials(awsAccessKey, awsSecretKey);
        S3Service s3Service = new RestS3Service(awsCredentials);
        SimpleThreadedStorageService simpleMulti = new SimpleThreadedStorageService(s3Service);
        S3Bucket bucket = s3Service.getBucket(Config.getInstance().get("aws.bucket"));
        final Path start = baseDir.resolve(PROCESSED_DIR);
        final List<StorageObject> objects = new ArrayList<>();
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relname = start.relativize(file.toAbsolutePath()).toString();
                if (!relname.matches(Config.getInstance().get("filter.exclude"))) {
                    S3Object fileObject = null;
                    try {
                        fileObject = new S3Object(file.toFile());
                    }
                    catch (NoSuchAlgorithmException e) {
                        e.printStackTrace();
                    }
                    fileObject.setName(relname);
                    objects.add(fileObject);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (clean) {
            simpleMulti.deleteObjects(bucket.getName(), s3Service.listObjects(bucket.getName()));
        }
        simpleMulti.putObjects(bucket.getName(), objects.toArray(new StorageObject[objects.size()]));
    }

}
