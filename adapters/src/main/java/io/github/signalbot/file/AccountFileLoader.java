package io.github.signalbot.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.signalbot.config.PortfolioProperties;
import io.github.signalbot.domain.port.SourceFailureException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Parses the hand-edited YAML portfolio file on each read (so edits take effect live). */
@Component
@ConditionalOnProperty(prefix = "signalbot.portfolio", name = "source", havingValue = "file")
public class AccountFileLoader {

    static final String SOURCE = "portfolio-file";

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    private final PortfolioProperties props;

    public AccountFileLoader(PortfolioProperties props) {
        this.props = props;
    }

    public FileDocument load() {
        Path path = Path.of(props.file());
        if (!Files.exists(path)) {
            throw new SourceFailureException(SOURCE, "portfolio.source=file but file not found: " + path.toAbsolutePath());
        }
        try {
            FileDocument doc = yaml.readValue(Files.readString(path), FileDocument.class);
            if (doc == null) {
                throw new SourceFailureException(SOURCE, "file is empty: " + path);
            }
            return doc;
        } catch (IOException e) {
            throw new SourceFailureException(SOURCE, "failed to read " + path + ": " + e.getMessage(), e);
        }
    }
}
