package org.example;

import org.openrewrite.ExecutionContext;
import org.openrewrite.FileAttributes;
import org.openrewrite.PrintOutputCapture;
import org.openrewrite.Result;
import org.openrewrite.binary.Binary;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.quark.Quark;
import org.openrewrite.remote.Remote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class RewriteRun extends AbstractRewrite {
    private static final Logger logger = LoggerFactory.getLogger(AbstractRewrite.class);

    public void execute() {
        ExecutionContext ctx = executionContext();
        ResultsContainer results = listResults(ctx);
        @Nullable RuntimeException firstException = results.getFirstException();
        if (firstException != null) {
            logger.error("The recipe produced an error. Please report this to the recipe author.");
            throw firstException;
        }

        if (results.isNotEmpty()) {
            for (Result result : results.generated) {
                assert result.getAfter() != null;
                logger.warn("Generated new file " +
                        result.getAfter().getSourcePath().normalize() +
                        " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.deleted) {
                assert result.getBefore() != null;
                logger.warn("Deleted file " +
                        result.getBefore().getSourcePath().normalize() +
                        " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.moved) {
                assert result.getAfter() != null;
                assert result.getBefore() != null;
                logger.warn("File has been moved from " +
                        result.getBefore().getSourcePath().normalize() + " to " +
                        result.getAfter().getSourcePath().normalize() + " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.refactoredInPlace) {
                assert result.getBefore() != null;
                logger.warn("Changes have been made to " +
                        result.getBefore().getSourcePath().normalize() +
                        " by:");
                logRecipesThatMadeChanges(result);
            }

            logger.warn("Please review and commit the results.");

            try {
                for (Result result : results.generated) {
                    assert result.getAfter() != null;
                    writeAfter(results.getProjectRoot(), result, ctx);
                }
                for (Result result : results.deleted) {
                    assert result.getBefore() != null;
                    Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath()).normalize();
                    boolean deleteSucceeded = originalLocation.toFile().delete();
                    if (!deleteSucceeded) {
                        throw new IOException("Unable to delete file " + originalLocation.toAbsolutePath());
                    }
                }
                for (Result result : results.moved) {
                    // Should we try to use git to move the file first, and only if that fails fall back to this?
                    assert result.getBefore() != null;
                    Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath());
                    File originalParentDir = originalLocation.toFile().getParentFile();

                    assert result.getAfter() != null;
                    // Ensure directories exist in case something was moved into a hitherto non-existent package
                    Path afterLocation = results.getProjectRoot().resolve(result.getAfter().getSourcePath());
                    File afterParentDir = afterLocation.toFile().getParentFile();
                    // Rename the directory if its name case has been changed, e.g. camel case to lower case.
                    if (afterParentDir.exists()
                            && afterParentDir.getAbsolutePath().equalsIgnoreCase((originalParentDir.getAbsolutePath()))
                            && !afterParentDir.getAbsolutePath().equals(originalParentDir.getAbsolutePath())) {
                        if (!originalParentDir.renameTo(afterParentDir)) {
                            throw new RuntimeException("Unable to rename directory from " + originalParentDir.getAbsolutePath() + " To: " + afterParentDir.getAbsolutePath());
                        }
                    } else if (!afterParentDir.exists() && !afterParentDir.mkdirs()) {
                        throw new RuntimeException("Unable to create directory " + afterParentDir.getAbsolutePath());
                    }
                    if (result.getAfter() instanceof Quark) {
                        // We don't know the contents of a Quark, but we can move it
                        Files.move(originalLocation, results.getProjectRoot().resolve(result.getAfter().getSourcePath()));
                    } else {
                        // On Mac this can return "false" even when the file was deleted, so skip the check
                        //noinspection ResultOfMethodCallIgnored
                        originalLocation.toFile().delete();
                        writeAfter(results.getProjectRoot(), result, ctx);
                    }
                }
                for (Result result : results.refactoredInPlace) {
                    assert result.getBefore() != null;
                    assert result.getAfter() != null;
                    writeAfter(results.getProjectRoot(), result, ctx);
                }
                List<Path> emptyDirectories = results.newlyEmptyDirectories();
                if (!emptyDirectories.isEmpty()) {
                    logger.info("Removing " + emptyDirectories.size() + " newly empty directories:");
                    for (Path emptyDirectory : emptyDirectories) {
                        logger.info("  " + emptyDirectory);
                        Files.delete(emptyDirectory);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to rewrite source files", e);
            }
        }
    }

    private static void writeAfter(Path root, Result result, ExecutionContext ctx) {
        if (result.getAfter() == null || result.getAfter() instanceof Quark) {
            return;
        }
        Path targetPath = root.resolve(result.getAfter().getSourcePath());
        File targetFile = targetPath.toFile();
        if (!targetFile.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            targetFile.getParentFile().mkdirs();
        }
        if (result.getAfter() instanceof Binary) {
            try (FileOutputStream sourceFileWriter = new FileOutputStream(targetFile)) {
                sourceFileWriter.write(((Binary) result.getAfter()).getBytes());
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        } else if (result.getAfter() instanceof Remote) {
            Remote remote = (Remote) result.getAfter();
            try (FileOutputStream sourceFileWriter = new FileOutputStream(targetFile)) {
                InputStream source = remote.getInputStream(ctx);
                byte[] buf = new byte[4096];
                int length;
                while ((length = source.read(buf)) > 0) {
                    sourceFileWriter.write(buf, 0, length);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        } else if (!(result.getAfter() instanceof Quark)) {
            // Don't attempt to write to a Quark; it has already been logged as change that has been made
            Charset charset = result.getAfter().getCharset() == null ? StandardCharsets.UTF_8 : result.getAfter().getCharset();
            try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(targetPath, charset)) {
                sourceFileWriter.write(result.getAfter().printAll(new PrintOutputCapture<>(0, new SanitizedMarkerPrinter())));
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        }
        if (result.getAfter().getFileAttributes() != null) {
            FileAttributes fileAttributes = result.getAfter().getFileAttributes();
            if (targetFile.canRead() != fileAttributes.isReadable()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.setReadable(fileAttributes.isReadable());
            }
            if (targetFile.canWrite() != fileAttributes.isWritable()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.setWritable(fileAttributes.isWritable());
            }
            if (targetFile.canExecute() != fileAttributes.isExecutable()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.setExecutable(fileAttributes.isExecutable());
            }
        }
    }

}
