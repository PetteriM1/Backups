package me.petterim1.backups;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class App {

    private static final Properties CONFIG = new Properties();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("-dd-MM-yyyy-HH-mm-ss");

    public static void main(String[] args) throws IOException {
        System.out.print((char) 0x1b + "]0;Backups" + (char) 0x07);
        log("Backups by PetteriM1");
        log("--------------------");
        log("Loading config...");
        loadConfig();
        checkConfig();
        enterLoop();
    }

    private static void log(String text) {
        System.out.println(text);
    }

    private static void enterLoop() {
        int hours = Integer.parseInt(CONFIG.getProperty("backup_hours"));
        log("Starting backup task with " + hours + " hours delay...");
        SCHEDULER.scheduleAtFixedRate(App::doBackups, hours, hours, TimeUnit.HOURS);
        Scanner scanner = new Scanner(System.in);
        log("Started! You can type 'backup' to start manual backup or 'stop' to quit.");
        while (true) {
            String cmd = scanner.nextLine();
            if ("backup".equalsIgnoreCase(cmd)) {
                SCHEDULER.execute(App::doBackups);
            } else if ("stop".equalsIgnoreCase(cmd)) {
                log("Stopping...");
                SCHEDULER.shutdown();
                try {
                    SCHEDULER.awaitTermination(5, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                System.exit(0);
            }
        }
    }

    private static void doBackups() {
        log("Creating backup...");
        try {
            File sourceFolder = new File(CONFIG.getProperty("source_folder"));
            if (!sourceFolder.isDirectory()) {
                log("Backup failed! source_folder is not a directory");
                return;
            }
            File targetFolder = new File(CONFIG.getProperty("target_folder"));
            targetFolder.mkdir();
            ZipFile zip = new ZipFile(new File(targetFolder.getPath() + "/" + sourceFolder.getName() + DATE_FORMAT.format(new Date(System.currentTimeMillis())) + ".zip"));
            ZipParameters parameters = new ZipParameters();
            parameters.setCompressionLevel(CompressionLevel.HIGHER); // TODO: config
            zip.addFolder(sourceFolder, parameters);
            log("Backup finished: " + zip);
            deleteOldBackups(targetFolder);
            secondaryBackup(zip);
            System.gc();
        } catch (Exception ex) {
            throw new RuntimeException("Backup failed!", ex);
        }
    }

    private static void secondaryBackup(ZipFile zip) throws IOException {
        String secondaryTargetPath = CONFIG.getProperty("secondary_target");
        if (secondaryTargetPath.trim().length() > 1) {
            if (SCHEDULER.isShutdown() || SCHEDULER.isTerminated()) {
                log("Skipping secondary backup update due to shutdown");
                return;
            }
            log("Updating secondary backup...");
            File targetFolder = new File(secondaryTargetPath);
            targetFolder.mkdir();
            File[] files = targetFolder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file != null && file.isFile() && file.getName().toLowerCase().endsWith(".zip")) {
                        if (file.delete()) {
                            log("Deleted old secondary backup: " + file.getName());
                        }
                    }
                }
            }
            log("Copying backup...");
            Files.copy(zip.getFile().toPath(), Paths.get(targetFolder.getPath() + File.separatorChar + zip.getFile().getName()));
            log("Done!");
        }
    }

    private static void deleteOldBackups(File targetFolder) {
        log("Deleting old backups...");
        int hours = Integer.parseInt(CONFIG.getProperty("keep_old_hours"));
        int count = 0;
        File[] files = targetFolder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file != null && file.isFile() && file.getName().toLowerCase().endsWith(".zip") && (System.currentTimeMillis() - file.lastModified()) / 1000 / 60 / 60 > hours) {
                    if (file.delete()) {
                        log("Deleted " + file.getName());
                        count++;
                    }
                }
            }
        }
        log("Done! " + count + " deleted");
    }

    private static void loadConfig() throws IOException {
        if (!new File("config.txt").exists()) {
            log("No config.txt found, creating an empty config...");
            exportDefaultConfig();
        }
        FileInputStream propsInput = new FileInputStream("config.txt");
        CONFIG.load(propsInput);
        propsInput.close();
        log("Config loaded: " + CONFIG);
    }

    private static void exportDefaultConfig() throws IOException {
        InputStream stream = null;
        OutputStream resStreamOut = null;
        try {
            stream = App.class.getClassLoader().getResourceAsStream("config.txt.empty");
            if (stream == null) {
                throw new RuntimeException("Cannot get 'config.txt.empty' from the jar file!");
            }
            resStreamOut = Files.newOutputStream(Paths.get(new File("config.txt").toURI()));
            byte[] buffer = new byte[4096];
            int readBytes;
            while ((readBytes = stream.read(buffer)) > 0) {
                resStreamOut.write(buffer, 0, readBytes);
            }
        } finally {
            if (stream != null) {
                stream.close();
            }
            if (resStreamOut != null) {
                resStreamOut.close();
            }
        }
    }

    private static void checkConfig() {
        try {
            if (Integer.parseInt(CONFIG.getProperty("backup_hours")) < 1) throw new RuntimeException("backup_hours must be a positive integer");
            if (Integer.parseInt(CONFIG.getProperty("keep_old_hours")) < 1) throw new RuntimeException("keep_old_hours must be a positive integer");
            if (CONFIG.getProperty("source_folder").isEmpty()) throw new RuntimeException("source_folder must not be empty");
            if (CONFIG.getProperty("target_folder").isEmpty()) throw new RuntimeException("target_folder must not be empty");
        } catch (NumberFormatException ex) {
            throw new RuntimeException("Failed to parse integer", ex);
        }
    }
}
