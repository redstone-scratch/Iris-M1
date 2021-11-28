import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import io.github.coolcrabs.brachyura.compiler.java.BrachyuraJavaFileManager;
import io.github.coolcrabs.brachyura.compiler.java.JavaCompilation;
import io.github.coolcrabs.brachyura.decompiler.BrachyuraDecompiler;
import io.github.coolcrabs.brachyura.dependency.JavaJarDependency;
import io.github.coolcrabs.brachyura.fabric.FabricLoader;
import io.github.coolcrabs.brachyura.fabric.FabricMaven;
import io.github.coolcrabs.brachyura.fabric.FabricProject;
import io.github.coolcrabs.brachyura.ide.IdeProject;
import io.github.coolcrabs.brachyura.ide.IdeProject.IdeProjectBuilder;
import io.github.coolcrabs.brachyura.ide.IdeProject.RunConfig.RunConfigBuilder;
import io.github.coolcrabs.brachyura.mappings.Namespaces;
import io.github.coolcrabs.brachyura.mappings.tinyremapper.MappingTreeMappingProvider;
import io.github.coolcrabs.brachyura.mappings.tinyremapper.RemapperProcessor;
import io.github.coolcrabs.brachyura.maven.MavenId;
import io.github.coolcrabs.brachyura.processing.ProcessingEntry;
import io.github.coolcrabs.brachyura.processing.ProcessorChain;
import io.github.coolcrabs.brachyura.processing.sinks.ZipProcessingSink;
import io.github.coolcrabs.brachyura.processing.sources.DirectoryProcessingSource;
import io.github.coolcrabs.brachyura.processing.sources.ProcessingSponge;
import io.github.coolcrabs.brachyura.util.AtomicFile;
import io.github.coolcrabs.brachyura.util.JvmUtil;
import io.github.coolcrabs.brachyura.util.PathUtil;
import io.github.coolcrabs.brachyura.util.Util;
import net.fabricmc.accesswidener.AccessWidenerReader;
import net.fabricmc.accesswidener.AccessWidenerVisitor;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.TinyRemapper;

public class Buildscript extends FabricProject {
    static final boolean SODIUM = false;

    @Override
    public String getMcVersion() {
        return "1.16.5";
    }

    @Override
    public MappingTree createMappings() {
        return createMojmap();
    }

    @Override
    public FabricLoader getLoader() {
        return new FabricLoader(FabricMaven.URL, FabricMaven.loader("0.12.5"));
    }

    @Override
    public Path getSrcDir() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Consumer<AccessWidenerVisitor> getAw() {
        return v -> {
            try {
                new AccessWidenerReader(v).read(Files.newBufferedReader(getResourcesDir().resolve("iris.accesswidener")), Namespaces.NAMED);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    @Override
    public void getModDependencies(ModDependencyCollector d) {
        d.addMaven(FabricMaven.URL, new MavenId(FabricMaven.GROUP_ID + ".fabric-api", "fabric-resource-loader-v0", "0.4.8+3cc0f0907d"), ModDependencyFlag.COMPILE, ModDependencyFlag.RUNTIME, ModDependencyFlag.JIJ);
        d.addMaven(FabricMaven.URL, new MavenId(FabricMaven.GROUP_ID + ".fabric-api", "fabric-key-binding-api-v1", "1.0.5+3cc0f0907d"), ModDependencyFlag.COMPILE, ModDependencyFlag.RUNTIME, ModDependencyFlag.JIJ);
        if (SODIUM) {
            // d.addMaven("https://api.modrinth.com/maven", new MavenId("maven.modrinth", "sodium", "mc1.16.5-0.2.0"), ModDependencyFlag.COMPILE, ModDependencyFlag.RUNTIME); // TOOD missing sha1 hash
            try {
                Path target = getLocalBrachyuraPath().resolve("sodium1.16.5-0.2.0.jar");
                if (!Files.exists(target)) {
                    try (
                        AtomicFile f = new AtomicFile(target);
                        InputStream is = new URL("https://api.modrinth.com/maven/maven/modrinth/sodium/mc1.16.5-0.2.0/sodium-mc1.16.5-0.2.0.jar").openStream();
                    ) {
                        Files.copy(is, f.tempPath, StandardCopyOption.REPLACE_EXISTING);
                        f.commit();
                    }
                }
                d.add(new JavaJarDependency(target, null, null), ModDependencyFlag.COMPILE, ModDependencyFlag.RUNTIME);
            } catch (Exception e) {
                Util.sneak(e);
            }
        }
    }

    public Path[] paths(String subdir, boolean headers, boolean tocompile) {
        List<Path> r = new ArrayList<>();
        if (tocompile) {
            Collections.addAll(
                r,
                getProjectDir().resolve("src").resolve("main").resolve(subdir),
                getProjectDir().resolve("src").resolve("headers").resolve(subdir),
                getProjectDir().resolve("src").resolve("vendored").resolve(subdir)
            );
            if (SODIUM) {
                r.add(getProjectDir().resolve("src").resolve("sodiumCompatibility").resolve(subdir));
            } else {
                r.add(getProjectDir().resolve("src").resolve("noSodiumStub").resolve(subdir));
            }
        }
        if (headers) {
            r.add(getProjectDir().resolve("src").resolve("headers").resolve(subdir));
        }
        r.removeIf(p -> !Files.exists(p));
        return r.toArray(new Path[0]);
    }

    @Override
    public boolean build() {
        try {
            String mixinOut = "mixinmapout.tiny";
            JavaCompilation compilation = new JavaCompilation()
                .addOption(JvmUtil.compileArgs(JvmUtil.CURRENT_JAVA_VERSION, 8))
                .addOption(
                    "-AbrachyuraInMap=" + writeMappings4FabricStuff().toAbsolutePath().toString(),
                    "-AbrachyuraOutMap=" + mixinOut, // Remaps shadows etc
                    "-AbrachyuraInNamespace=" + Namespaces.NAMED,
                    "-AbrachyuraOutNamespace=" + Namespaces.INTERMEDIARY,
                    "-AoutRefMapFile=" + getModId() + "-refmap.json", // Remaps annotations
                    "-AdefaultObfuscationEnv=brachyura"
                )
                .addClasspath(getCompileDependencies());
            for (Path p : paths("java", false, true)) {
                compilation.addSourceDir(p);
            }
            for (Path p : paths("java", true, false)) {
                compilation.addSourcePathDir(p);
            }
            BrachyuraJavaFileManager fm = new BrachyuraJavaFileManager();
            if (!compilation.compile(fm)) return false;
            ProcessingSponge compilationOutput = new ProcessingSponge();
            fm.getProcessingSource().getInputs(compilationOutput);
            MemoryMappingTree compmappings = new MemoryMappingTree(true);
            mappings.get().accept(new MappingSourceNsSwitch(compmappings, Namespaces.NAMED));
            ProcessingEntry mixinMappings = compilationOutput.popEntry(mixinOut);
            if (mixinMappings != null) {
                try (Reader reader = new InputStreamReader(mixinMappings.in.get())) {
                    MappingReader.read(reader, MappingFormat.TINY_2, compmappings);
                }
            }
            ProcessingSponge trout = new ProcessingSponge();
            new ProcessorChain(
                new RemapperProcessor(TinyRemapper.newRemapper().withMappings(new MappingTreeMappingProvider(compmappings, Namespaces.NAMED, Namespaces.INTERMEDIARY)), getCompileDependencies())
            ).apply(trout, compilationOutput);
            try (AtomicFile atomicFile = new AtomicFile(getBuildJarPath())) {
                Files.deleteIfExists(atomicFile.tempPath);
                try (ZipProcessingSink out = new ZipProcessingSink(atomicFile.tempPath)) {
                    processResourcesChain().apply(out, new DirectoryProcessingSource(getResourcesDir()));
                    trout.getInputs(out);
                }
                atomicFile.commit();
            }
            return true;
        } catch (Exception e) {
            throw Util.sneak(e);
        }
    }

    @Override
    public IdeProject getIdeProject() {
        Path mappingsClasspath = writeMappings4FabricStuff().getParent().getParent();
        Path cwd = getProjectDir().resolve("run");
        PathUtil.createDirectories(cwd);
        ArrayList<Path> classpath = new ArrayList<>(runtimeDependencies.get().size() + 1);
        for (JavaJarDependency dependency : runtimeDependencies.get()) {
            classpath.add(dependency.jar);
        }
        classpath.add(mappingsClasspath);
        Path launchConfig = writeLaunchCfg();
        return new IdeProjectBuilder()
            .dependencies(ideDependencies.get())
            .sourcePaths(paths("java", true, true))
            .resourcePaths(paths("resources", true, true))
            .runConfigs(
                new RunConfigBuilder()
                    .name("Minecraft Client")
                    .cwd(cwd)
                    .mainClass("net.fabricmc.devlaunchinjector.Main")
                    .classpath(classpath)
                    .vmArgs(
                        "-Dfabric.dli.config=" + launchConfig.toString(),
                        "-Dfabric.dli.env=client",
                        "-Dfabric.dli.main=net.fabricmc.loader.launch.knot.KnotClient"
                    )
                .build(),
                new RunConfigBuilder()
                    .name("Minecraft Server")
                    .cwd(cwd)
                    .mainClass("net.fabricmc.devlaunchinjector.Main")
                    .classpath(classpath)
                    .vmArgs(
                        "-Dfabric.dli.config=" + launchConfig.toString(),
                        "-Dfabric.dli.env=server",
                        "-Dfabric.dli.main=net.fabricmc.loader.launch.knot.KnotServer"
                    )
                .build()
            )
        .build();
    }

    @Override
    public BrachyuraDecompiler decompiler() {
        return SODIUM ? null : super.decompiler();
    }
    
}