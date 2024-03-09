package org.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ProjectParser {
    public static Stream<SourceFile> listSourceFiles(Path projectDirectory) throws IOException {
        Stream<SourceFile> fileStream = Files.walk(Paths.get(projectDirectory.toUri()))
                .filter(Files::isRegularFile) // Filter out directories
                .filter(path -> path.toString().endsWith(".java")) // Filter by file extension, adjust as needed
                .map(SourceFile::new); // Map Path objects to SourceFile objects
        return (Stream<SourceFile>) fileStream.collect(Collectors.toList());
    }

    static class SourceFile {
        private Path filePath;

        public SourceFile(Path filePath) {
            this.filePath = filePath;
        }

        public Path getFilePath() {
            return filePath;
        }

        @Override
        public String toString() {
            return filePath.toString();
        }
    }
}
