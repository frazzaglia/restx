package restx.build.shell;

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.io.LineProcessor;
import com.googlecode.junittoolbox.ParallelParameterized;
import org.apache.maven.it.VerificationException;
import org.apache.maven.it.Verifier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import restx.build.*;
import restx.common.OSUtils;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(ParallelParameterized.class)
public class RestxModuleDescriptorsDiscrepanciesDetectorWithPomAndIvyTest {

    private enum GenerationType {
        IVY("module.ivy") {
            @Override
            public RestxBuild.Generator generator() {
                return new IvySupport();
            }

        }, POM("pom.xml") {
            @Override
            public RestxBuild.Generator generator() {
                return new MavenSupport();
            }

            class RunMavenEffectivePom implements Runnable {
                File targetPom;
                File effectivePom;

                public RunMavenEffectivePom(File targetPom, File effectivePom) {
                    this.targetPom = targetPom;
                    this.effectivePom = effectivePom;
                }

                @Override
                public void run() {

                    try {
                        Verifier mavenVerifier = new Verifier(targetPom.getParentFile().getAbsolutePath());

                        if (OSUtils.isMacOSX()) {
                            // on MacOSX, when using the verifier JAVA_HOME is not always properly detected
                            // sometimes it points to JRE, making javadoc call fail.
                            // Here is a workaround for that problem
                            String javaHome = System.getProperty("java.home");
                            if (javaHome.endsWith("jre")) {
                                javaHome = new File(javaHome).getParentFile().getCanonicalPath();
                                mavenVerifier.setEnvironmentVariable("JAVA_HOME", javaHome);
                            }
                        }
                        if(System.getProperty("skip-offline") == null) {
                            mavenVerifier.addCliOption("--offline");
                        }
                        mavenVerifier.addCliOption("-f "+targetPom.getAbsolutePath());
                        mavenVerifier.addCliOption("--no-snapshot-updates");
                        mavenVerifier.setSystemProperty("output", effectivePom.getAbsolutePath());
                        mavenVerifier.setAutoclean(false);
                        String logFileName = "___log.txt";
                        File logFile = targetPom.getParentFile().toPath().resolve(logFileName).toFile();
                        mavenVerifier.setLogFileName(logFileName);
                        mavenVerifier.executeGoal("org.apache.maven.plugins:maven-help-plugin:2.2:effective-pom");
                        mavenVerifier.verifyErrorFreeLog();

                        assertTrue(logFile.delete());
                        removeGeneratedLineIn(effectivePom);
                    } catch (VerificationException | IOException e) {
                        throw Throwables.propagate(e);
                    }

                    System.out.println("Over");
                }
            }

            @Override
            public void assertExistingAndGeneratedDescriptorsAreSimilar(File existingDescriptor, File generatedDescriptor, TemporaryFolder tempFolder) throws IOException {
                File existingEffectivePom = tempFolder.newFile("existingEffectivePom");
                File generatedEffectivePom = tempFolder.newFile("generatedEffectivePom");

                // I tried to execute those tasks through a fixedThreadPool(2) executor but ... doesn't detect some testing failures
                // (for unknown reasons)
                new RunMavenEffectivePom(existingDescriptor, existingEffectivePom).run();
                new RunMavenEffectivePom(generatedDescriptor, generatedEffectivePom).run();

                super.assertExistingAndGeneratedDescriptorsAreSimilar(existingEffectivePom, generatedEffectivePom, tempFolder);
            }

            private void removeGeneratedLineIn(File file) throws IOException {
                List<String> trimmedLines = com.google.common.io.Files.readLines(file, Charsets.UTF_8, new LineProcessor<List<String>>() {
                    List<String> result = Lists.newArrayList();

                    @Override
                    public boolean processLine(String line) throws IOException {
                        if(!line.contains("Generated by Maven Help Plugin on")) {
                            result.add(line);
                        }
                        return true;
                    }

                    @Override
                    public List<String> getResult() {
                        return result;
                    }
                });

                Files.write(file.toPath(), trimmedLines, Charsets.UTF_8, StandardOpenOption.CREATE);
            }
        };

        private String filename;
        GenerationType(String filename) {
            this.filename = filename;
        }

        public abstract RestxBuild.Generator generator();
        public void assertExistingAndGeneratedDescriptorsAreSimilar(File existingDescriptor, File generatedDescriptor, TemporaryFolder tempFolder) throws IOException {
            assertEquals(com.google.common.io.Files.toString(existingDescriptor, Charsets.UTF_8),
                    com.google.common.io.Files.toString(generatedDescriptor, Charsets.UTF_8));
        }
    }

    private final String moduleName;
    private final GenerationType generationType;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Parameterized.Parameters(name="{0}/{1}")
    public static Iterable<Object[]> data(){
        Path restxSourcesRootDir = getRestxSourcesRootDir();
        String[] directories = restxSourcesRootDir.toFile().list(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return new File(dir, name).isDirectory();
            }
        });

        List<Object[]> testingData = new ArrayList<>();
        for(String moduleName: directories) {
            if(Files.exists(restxSourcesRootDir.resolve(moduleName).resolve("md.restx.json"))) {
                for(GenerationType generationType: GenerationType.values()) {
                    if(Files.exists(restxSourcesRootDir.resolve(moduleName).resolve(generationType.filename))) {
                        testingData.add(new Object[]{ moduleName, generationType });
                    }
                }
            }
        }

        return testingData;
    }

    private static Path getRestxSourcesRootDir() {
        return Paths.get(System.getProperty("restxSourcesRootDir"));
    }

    public RestxModuleDescriptorsDiscrepanciesDetectorWithPomAndIvyTest(String moduleName, GenerationType generationType) {
        this.moduleName = moduleName;
        this.generationType = generationType;
    }

    @Test
    public void should_restx_module_descriptor_and_pom_or_ivy_descriptors_equivalents() throws IOException {
        Path restxSourcesRootDir = getRestxSourcesRootDir();
        Path moduleDirectory = restxSourcesRootDir.resolve(this.moduleName);
        File existingDescriptor = moduleDirectory.resolve(this.generationType.filename).toFile();

        ModuleDescriptor moduleDescriptor = new RestxJsonSupport().parse(moduleDirectory.resolve("md.restx.json"));
        // Generating the generated descriptor into the same directory than existingDescriptor, in case we would have file-tree
        // related stuff into the descriptor (particularly for maven)
        File generatedDescriptor = existingDescriptor.getParentFile().toPath().resolve("generated-"+this.generationType.name()+"-descriptor").toFile();
        assertTrue(generatedDescriptor.createNewFile());
        generatedDescriptor.deleteOnExit();

        try (Writer w = com.google.common.io.Files.newWriter(generatedDescriptor, Charsets.UTF_8)) {
            this.generationType.generator().generate(moduleDescriptor, w);
        }

        this.generationType.assertExistingAndGeneratedDescriptorsAreSimilar(existingDescriptor, generatedDescriptor, tempFolder);
    }
}
