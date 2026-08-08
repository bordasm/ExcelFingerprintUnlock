package hu.bordasm.excelautofillprobe;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class ProbeLog {
    private static final String FILE_NAME = "autofill-probe.log";
    private static final long MAX_BYTES = 128 * 1024;

    private ProbeLog() {
    }

    static synchronized void append(Context context, String event) {
        File file = logFile(context);
        try {
            if (file.length() > MAX_BYTES && !file.delete()) {
                return;
            }
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
                    .format(new Date());
            String safeEvent = event.replace('\r', ' ').replace('\n', ' ');
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                writer.write(time);
                writer.write("  ");
                writer.write(safeEvent);
                writer.write('\n');
            }
        } catch (Exception ignored) {
            // A diagnosztika hibája nem szakíthatja meg az Autofill-kérést.
        }
    }

    static synchronized String read(Context context) {
        File file = logFile(context);
        if (!file.isFile()) {
            return "(Még nincs Autofill-esemény.)";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append('\n');
            }
        } catch (Exception error) {
            return "(A diagnosztikai napló nem olvasható.)";
        }
        return result.toString();
    }

    static synchronized void clear(Context context) {
        File file = logFile(context);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static File logFile(Context context) {
        return new File(context.getNoBackupFilesDir(), FILE_NAME);
    }
}
