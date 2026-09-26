package labs.tx;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 测试把观察到的事实追加到 target/facts.tsv，作为证据的一部分。 */
public final class Facts {
    private Facts() {
    }

    public static synchronized void record(String key, Object value) {
        try {
            Path p = Path.of("target", "facts.tsv");
            Files.createDirectories(p.getParent());
            Files.writeString(p, key + "\t" + value + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
