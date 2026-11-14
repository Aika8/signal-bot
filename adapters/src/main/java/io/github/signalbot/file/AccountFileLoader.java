package io.github.signalbot.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.signalbot.config.PortfolioProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Parses the hand-edited YAML portfolio file on each read (so edits take effect live). */
@Component
@ConditionalOnProperty(prefix = "signalbot.portfolio", name = "source", havingValue = "file")
public class AccountFileLoader {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    private final PortfolioProperties props;

    public AccountFileLoader(PortfolioProperties props) {
        this.props = props;
    }

    public FileDocument load() {
        Path path = Path.of(props.file());
        if (!Files.exists(path)) {
            throw new IllegalStateException("portfolio.source=file but file not found: " + path.toAbsolutePath());
        }
        try {
            return yaml.readValue(Files.readString(path), FileDocument.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read portfolio file " + path + ": " + e.getMessage(), e);
        }
    }
}
