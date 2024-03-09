package org.example;

import org.openrewrite.*;
import org.openrewrite.config.Environment;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.internal.lang.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.openrewrite.marker.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

public class AbstractRewrite extends ConfigurableRewrite {

    private static final Logger logger = LoggerFactory.getLogger(AbstractRewrite.class);

    protected ExecutionContext executionContext() {
        return new InMemoryExecutionContext(t -> logger.debug(String.valueOf(t)));
    }

    protected Path getBuildRoot() {
        return Path.of("E:\\GSOC\\XMLtoJSONmavenPlugin");
    }

    protected Path repositoryRoot() {
        Path buildRoot = getBuildRoot();
        Path maybeBaseDir = buildRoot;
        while (maybeBaseDir != null && !Files.exists(maybeBaseDir.resolve(".git"))) {
            maybeBaseDir = maybeBaseDir.getParent();
        }
        if (maybeBaseDir == null) {
            return buildRoot;
        }
        return maybeBaseDir;
    }
    protected List<Result> runRecipe(Recipe recipe, LargeSourceSet sourceSet, ExecutionContext ctx) {
        logger.info("Running recipe(s)...");
        return recipe.run(sourceSet, ctx).getChangeset().getAllResults().stream()
                .filter(source -> {
                    // Remove ASTs originating from generated files
                    if (source.getBefore() != null) {
                        return source.getBefore().getMarkers().findFirst(Generated.class).isEmpty();
                    }
                    return true;
                })
                .collect(toList());
    }

    protected Environment environment() {
        Environment.Builder env = Environment.builder();
        env.scanClassLoader(getClass().getClassLoader());
        return env.build();
    }

    protected ResultsContainer listResults(ExecutionContext ctx)  {
        try {
            Path repositoryRoot = repositoryRoot();
            logger.info(String.format("Using active recipe(s) %s", getActiveRecipes()));

            Environment env = environment();

            assert activeRecipes != null;
            System.out.println(env.listRecipes());
            Recipe recipe = env.activateRecipes(activeRecipes);
            if (recipe.getRecipeList().isEmpty()) {
                logger.warn("No recipes were activated. ");
                return new ResultsContainer(repositoryRoot, emptyList());
            }

            logger.info("Validating active recipes...");
            List<Validated<Object>> validations = new ArrayList<>();
            recipe.validateAll(ctx, validations);
            List<Validated.Invalid<Object>> failedValidations = validations.stream().map(Validated::failures)
                    .flatMap(Collection::stream).toList();
            if (!failedValidations.isEmpty()) {
                failedValidations.forEach(failedValidation -> logger.error(
                        "Recipe validation error in " + failedValidation.getProperty() + ": " +
                                failedValidation.getMessage(), failedValidation.getException()));
            }

            LargeSourceSet sourceSet = loadSourceSet(repositoryRoot, env, ctx);

            List<Result> results = runRecipe(recipe, sourceSet, ctx);

            return new ResultsContainer(repositoryRoot, results);
        } catch (IOException e) {
            logger.error("Dependency resolution required");
        }
        return null;
    }
    protected LargeSourceSet loadSourceSet(Path repositoryRoot, Environment env, ExecutionContext ctx) throws IOException {
        ProjectParser projectParser = new ProjectParser();
        List<SourceFile> sourceFileList = (List<SourceFile>) projectParser.listSourceFiles(repositoryRoot);
        return new InMemoryLargeSourceSet(sourceFileList);
    }

    private List<SourceFile> convertToList(Stream<SourceFile> fileStream) {
        return fileStream.collect(Collectors.toList());
    }

    public static class ResultsContainer {
        final Path projectRoot;
        final List<Result> generated = new ArrayList<>();
        final List<Result> deleted = new ArrayList<>();
        final List<Result> moved = new ArrayList<>();
        final List<Result> refactoredInPlace = new ArrayList<>();

        public Path getProjectRoot() {
            return projectRoot;
        }

        public List<Path> newlyEmptyDirectories() {
            Set<Path> maybeEmptyDirectories = new LinkedHashSet<>();
            for (Result result : moved) {
                assert result.getBefore() != null;
                maybeEmptyDirectories.add(projectRoot.resolve(result.getBefore().getSourcePath()).getParent());
            }
            for (Result result : deleted) {
                assert result.getBefore() != null;
                maybeEmptyDirectories.add(projectRoot.resolve(result.getBefore().getSourcePath()).getParent());
            }
            if (maybeEmptyDirectories.isEmpty()) {
                return Collections.emptyList();
            }
            List<Path> emptyDirectories = new ArrayList<>(maybeEmptyDirectories.size());
            for (Path maybeEmptyDirectory : maybeEmptyDirectories) {
                try (Stream<Path> contents = Files.list(maybeEmptyDirectory)) {
                    if (contents.findAny().isPresent()) {
                        continue;
                    }
                    Files.delete(maybeEmptyDirectory);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return emptyDirectories;
        }

        public ResultsContainer(Path projectRoot, Collection<Result> results) {
            this.projectRoot = projectRoot;
            for (Result result : results) {
                if (result.getBefore() == null && result.getAfter() == null) {
                    // This situation shouldn't happen / makes no sense, log and skip
                    continue;
                }
                if (result.getBefore() == null && result.getAfter() != null) {
                    generated.add(result);
                } else if (result.getBefore() != null && result.getAfter() == null) {
                    deleted.add(result);
                } else if (result.getBefore() != null && result.getAfter() != null && !result.getBefore().getSourcePath().equals(result.getAfter().getSourcePath())) {
                    moved.add(result);
                } else {
                    if (!result.diff(Paths.get(""), new FencedMarkerPrinter(), true).isEmpty()) {
                        refactoredInPlace.add(result);
                    }
                }
            }
        }
        private static class FencedMarkerPrinter implements PrintOutputCapture.MarkerPrinter {
            @Override
            public String beforeSyntax(Marker marker, Cursor cursor, UnaryOperator<String> commentWrapper) {
                return marker instanceof SearchResult || marker instanceof Markup ? "{{" + marker.getId() + "}}" : "";
            }

            @Override
            public String afterSyntax(Marker marker, Cursor cursor, UnaryOperator<String> commentWrapper) {
                return marker instanceof SearchResult || marker instanceof Markup ? "{{" + marker.getId() + "}}" : "";
            }
        }
        @Nullable
        public RuntimeException getFirstException() {
            for (Result result : generated) {
                for (RuntimeException error : getRecipeErrors(result)) {
                    return error;
                }
            }
            for (Result result : deleted) {
                for (RuntimeException error : getRecipeErrors(result)) {
                    return error;
                }
            }
            for (Result result : moved) {
                for (RuntimeException error : getRecipeErrors(result)) {
                    return error;
                }
            }
            for (Result result : refactoredInPlace) {
                for (RuntimeException error : getRecipeErrors(result)) {
                    return error;
                }
            }
            return null;
        }

        private List<RuntimeException> getRecipeErrors(Result result) {
            List<RuntimeException> exceptions = new ArrayList<>();
            new TreeVisitor<Tree, Integer>() {
                @Override
                public Tree preVisit(Tree tree, Integer integer) {
                    Markers markers = tree.getMarkers();
                    markers.findFirst(Markup.Error.class).ifPresent(e -> {
                        Optional<SourceFile> sourceFile = Optional.ofNullable(getCursor().firstEnclosing(SourceFile.class));
                        String sourcePath = sourceFile.map(SourceFile::getSourcePath).map(Path::toString).orElse("<unknown>");
                        exceptions.add(new RuntimeException("Error while visiting " + sourcePath + ": " + e.getDetail()));
                    });
                    return tree;
                }
            }.visit(result.getAfter(), 0);
            return exceptions;
        }

        public boolean isNotEmpty() {
            return !generated.isEmpty() || !deleted.isEmpty() || !moved.isEmpty() || !refactoredInPlace.isEmpty();
        }

}
    protected void logRecipesThatMadeChanges(Result result) {
        String indent = "    ";
        String prefix = "    ";
        for (RecipeDescriptor recipeDescriptor : result.getRecipeDescriptorsThatMadeChanges()) {
            logRecipe(recipeDescriptor, prefix);
            prefix = prefix + indent;
        }
    }

    private void logRecipe(RecipeDescriptor rd, String prefix) {
        StringBuilder recipeString = new StringBuilder(prefix + rd.getName());
        if (!rd.getOptions().isEmpty()) {
            String opts = rd.getOptions().stream().map(option -> {
                        if (option.getValue() != null) {
                            return option.getName() + "=" + option.getValue();
                        }
                        return null;
                    }
            ).filter(Objects::nonNull).collect(joining(", "));
            if (!opts.isEmpty()) {
                recipeString.append(": {").append(opts).append("}");
            }
        }
        logger.warn(recipeString.toString());
        for (RecipeDescriptor rchild : rd.getRecipeList()) {
            logRecipe(rchild, prefix + "    ");
        }
    }

}
