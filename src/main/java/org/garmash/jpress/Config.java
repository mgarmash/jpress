package org.garmash.jpress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;

/**
 * @author Max Garmash
 */
public class Config {
    private Properties properties = new Properties();

    private static Config instance = new Config();

    public static Config getInstance() {
        return instance;
    }

    private Config() {
        try {
            InputStream applicationProperties = getClass().getClassLoader().getResourceAsStream("application.properties");
            properties.load(new BufferedReader(new InputStreamReader(applicationProperties)));
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String get(String key) {
        return properties.get(key).toString();
    }
}
