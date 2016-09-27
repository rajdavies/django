/**
 * Copyright 2005-2015 Red Hat, Inc.
 * <p>
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.jar.AbstractJarMojo;

import static io.fabric8.FileHelper.loadText;
import static io.fabric8.JSonSchemaHelper.parseJsonSchema;

@Mojo(name = "jar", defaultPhase = LifecyclePhase.PACKAGE, requiresProject = true, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.RUNTIME)
public class ConnectorMojo extends AbstractJarMojo {

    /**
     * Directory containing the classes and resource files that should be packaged into the JAR.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private File classesDirectory;

    @Override
    protected File getClassesDirectory() {
        return classesDirectory;
    }

    @Override
    protected String getClassifier() {
        return "camel-connector";
    }

    @Override
    protected String getType() {
        return "jar";
    }

    @Override
    public File createArchive() throws MojoExecutionException {

        // find the component dependency and get its .json file

        File file = new File(classesDirectory, "camel-connector.json");
        if (file.exists()) {
            try {

                ObjectMapper mapper = new ObjectMapper();
                Map dto = mapper.readValue(file, Map.class);

                File schema = embedCamelComponentSchema(file);
                if (schema != null) {
                    String json = loadText(new FileInputStream(schema));

                    List<Map<String, String>> rows = parseJsonSchema("component", json, false);
                    String header = buildComponentHeaderSchema(rows, dto);
                    getLog().debug(header);

                    rows = parseJsonSchema("componentProperties", json, true);
                    // we do not offer editing component properties (yet) so clear the rows
                    rows.clear();
                    String componentOptions = buildComponentOptionsSchema(rows, dto);
                    getLog().debug(componentOptions);

                    rows = parseJsonSchema("properties", json, true);
                    String endpointOptions = buildEndpointOptionsSchema(rows, dto);
                    getLog().debug(endpointOptions);

                    // generate the json file
                    StringBuilder jsonSchema = new StringBuilder();
                    jsonSchema.append("{\n");
                    jsonSchema.append(header);
                    jsonSchema.append(componentOptions);
                    jsonSchema.append(endpointOptions);
                    jsonSchema.append("}\n");

                    String newJson = jsonSchema.toString();

                    // parse ourselves
                    rows = parseJsonSchema("component", newJson, false);
                    String newScheme = getOption(rows, "scheme");

                    // write the json file to the target directory as if camel apt would do it
                    String javaType = (String) dto.get("javaType");
                    String dir = javaType.substring(0, javaType.lastIndexOf("."));
                    dir = dir.replace('.', '/');
                    File subDir = new File(classesDirectory, dir);
                    String name = newScheme + ".json";
                    File out = new File(subDir, name);

                    FileOutputStream fos = new FileOutputStream(out, false);
                    fos.write(newJson.getBytes());
                    fos.close();
                }

                // build json schema for component that only has the selectable options
            } catch (Exception e) {
                throw new MojoExecutionException("Error in connector-maven-plugin", e);
            }
        }

        return super.createArchive();
    }

    private String extractJavaType(String scheme) throws Exception {
        File file = new File(classesDirectory, "META-INF/services/org/apache/camel/component/" + scheme);
        if (file.exists()) {
            List<String> lines = loadFile(file);
            String fqn = extractClass(lines);
            return fqn;
        }
        return null;
    }

    private String getOption(List<Map<String, String>> rows, String key) {
        for (Map<String, String> row : rows) {
            if (row.containsKey(key)) {
                return row.get(key);
            }
        }
        return null;
    }

    private String buildComponentOptionsSchema(List<Map<String, String>> rows, Map dto) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();

        StringBuilder sb = new StringBuilder();
        sb.append(" \"componentProperties\": {\n");

        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            String key = row.get("name");
            row.remove("name");
            String line = mapper.writeValueAsString(row);

            sb.append("    \"" + key + "\": ");
            sb.append(line);
            if (i < rows.size() - 1) {
                sb.append(",\n");
            } else {
                sb.append("\n");
            }
        }

        sb.append("  },\n");
        return sb.toString();
    }

    private String buildEndpointOptionsSchema(List<Map<String, String>> rows, Map dto) throws JsonProcessingException {
        // find the endpoint options
        List options = (List) dto.get("endpointOptions");

        ObjectMapper mapper = new ObjectMapper();

        StringBuilder sb = new StringBuilder();
        sb.append(" \"properties\": {\n");

        boolean first = true;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            String key = row.get("name");
            row.remove("name");

            // TODO: if no options should we include all by default instead?
            if (options == null || !options.contains(key)) {
                continue;
            }

            // we should build the json as one-line which is how Camel does it today
            // which makes its internal json parser support loading our generated schema file
            String line = mapper.writeValueAsString(row);

            if (!first) {
                sb.append(",\n");
            }
            sb.append("    \"" + key + "\": ");
            sb.append(line);

            first = false;
        }
        if (!first) {
            sb.append("\n");
        }

        sb.append("  }\n");
        return sb.toString();
    }

    private String buildComponentHeaderSchema(List<Map<String, String>> rows, Map dto) throws Exception {
        String baseScheme = (String) dto.get("scheme");
        String source = (String) dto.get("source");
        String title = (String) dto.get("name");
        String scheme = camelCaseToDash(title);
        String baseSyntax = getOption(rows, "syntax");
        String syntax = baseSyntax.replaceFirst(baseScheme, scheme);

        String description = (String) dto.get("description");
        // dto has labels
        String label = null;
        List<String> labels = (List<String>) dto.get("labels");
        if (labels != null) {
            CollectionStringBuffer csb = new CollectionStringBuffer(",");
            for (String s : labels) {
                csb.append(s);
            }
            label = csb.toString();
        }
        String async = getOption(rows, "async");
        String producerOnly = "To".equals(source) ? "true" : null;
        String consumerOnly = "From".equals(source) ? "true" : null;
        String lenientProperties = getOption(rows, "lenientProperties");
        String javaType = extractJavaType(scheme);
        String groupId = getProject().getGroupId();
        String artifactId = getProject().getArtifactId();
        String version = getProject().getVersion();

        StringBuilder sb = new StringBuilder();
        sb.append(" \"component\": {\n");
        sb.append("    \"kind\": \"component\",\n");
        sb.append("    \"baseScheme\": \"" + baseScheme + "\",\n");
        sb.append("    \"scheme\": \"" + scheme + "\",\n");
        sb.append("    \"syntax\": \"" + syntax + "\",\n");
        sb.append("    \"title\": \"" + title + "\",\n");
        if (description != null) {
            sb.append("    \"description\": \"" + description + "\",\n");
        }
        if (label != null) {
            sb.append("    \"label\": \"" + label + "\",\n");
        }
        sb.append("    \"deprecated\": \"false\",\n");
        if (async != null) {
            sb.append("    \"async\": \"" + async + "\",\n");
        }
        if (producerOnly != null) {
            sb.append("    \"producerOnly\": \"" + producerOnly + "\",\n");
        } else if (consumerOnly != null) {
            sb.append("    \"consumerOnly\": \"" + consumerOnly + "\",\n");
        }
        if (lenientProperties != null) {
            sb.append("    \"lenientProperties\": \"" + lenientProperties + "\",\n");
        }
        sb.append("    \"javaType\": \"" + javaType + "\",\n");
        sb.append("    \"groupId\": \"" + groupId + "\",\n");
        sb.append("    \"artifactId\": \"" + artifactId + "\",\n");
        sb.append("    \"version\": \"" + version + "\"\n");
        sb.append("  },\n");

        return sb.toString();
    }

    /**
     * Finds and embeds the Camel component JSon schema file
     */
    private File embedCamelComponentSchema(File file) throws MojoExecutionException {
        try {
            List<String> json = loadFile(file);

            String scheme = extractScheme(json);
            String groupId = extractGroupId(json);
            String artifactId = extractArtifactId(json);
            String version = extractVersion(json); // version not in use

            // find the artifact on the classpath that has the Camel component this connector is using
            // then we want to grab its json schema file to embed in this JAR so we have all files together

            if (scheme != null && groupId != null && artifactId != null) {
                for (Artifact artifact : getProject().getDependencyArtifacts()) {
                    if ("jar".equals(artifact.getType())) {
                        if (groupId.equals(artifact.getGroupId()) && artifactId.equals(artifact.getArtifactId())) {
                            // load the component file inside the file
                            URL url = new URL("file:" + artifact.getFile());
                            URLClassLoader child = new URLClassLoader(new URL[]{url}, this.getClass().getClassLoader());

                            InputStream is = child.getResourceAsStream("META-INF/services/org/apache/camel/component/" + scheme);
                            if (is != null) {
                                List<String> lines = loadFile(is);
                                String fqn = extractClass(lines);
                                is.close();

                                // only keep package
                                String pck = fqn.substring(0, fqn.lastIndexOf("."));
                                String name = pck.replace(".", "/") + "/" + scheme + ".json";

                                is = child.getResourceAsStream(name);
                                if (is != null) {
                                    List<String> schema = loadFile(is);
                                    is.close();

                                    // write schema to file
                                    File out = new File(classesDirectory, "camel-component-schema.json");
                                    FileOutputStream fos = new FileOutputStream(out, false);
                                    for (String line : schema) {
                                        fos.write(line.getBytes());
                                        fos.write("\n".getBytes());
                                    }
                                    fos.close();

                                    getLog().info("Embedded camel-component-schema.json file for Camel component " + scheme);

                                    return out;
                                }
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            throw new MojoExecutionException("Cannot read file camel-connector.json", e);
        }

        return null;
    }

    private String extractClass(List<String> lines) {
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("class=")) {
                return line.substring(6);
            }
        }
        return null;
    }

    private String extractScheme(List<String> json) {
        for (String line : json) {
            line = line.trim();
            if (line.startsWith("\"scheme\":")) {
                String answer = line.substring(10);
                return answer.substring(0, answer.length() - 2);
            }
        }
        return null;
    }

    private String extractGroupId(List<String> json) {
        for (String line : json) {
            line = line.trim();
            if (line.startsWith("\"groupId\":")) {
                String answer = line.substring(11);
                return answer.substring(0, answer.length() - 2);
            }
        }
        return null;
    }

    private String extractArtifactId(List<String> json) {
        for (String line : json) {
            line = line.trim();
            if (line.startsWith("\"artifactId\":")) {
                String answer = line.substring(14);
                return answer.substring(0, answer.length() - 2);
            }
        }
        return null;
    }

    private String extractVersion(List<String> json) {
        for (String line : json) {
            line = line.trim();
            if (line.startsWith("\"version\":")) {
                String answer = line.substring(11);
                return answer.substring(0, answer.length() - 2);
            }
        }
        return null;
    }

    private List<String> loadFile(File file) throws Exception {
        List<String> lines = new ArrayList<>();
        LineNumberReader reader = new LineNumberReader(new FileReader(file));

        String line;
        do {
            line = reader.readLine();
            if (line != null) {
                lines.add(line);
            }
        } while (line != null);
        reader.close();

        return lines;
    }

    private List<String> loadFile(InputStream fis) throws Exception {
        List<String> lines = new ArrayList<>();
        LineNumberReader reader = new LineNumberReader(new InputStreamReader(fis));

        String line;
        do {
            line = reader.readLine();
            if (line != null) {
                lines.add(line);
            }
        } while (line != null);
        reader.close();

        return lines;
    }

    public static String camelCaseToDash(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        boolean dash = false;

        for (char c : value.toCharArray()) {
            // skip dash in start
            if (sb.length() > 0 & Character.isUpperCase(c)) {
                dash = true;
            }
            if (dash) {
                sb.append('-');
                sb.append(Character.toLowerCase(c));
            } else {
                // lower case first
                if (sb.length() == 0) {
                    sb.append(Character.toLowerCase(c));
                } else {
                    sb.append(c);
                }
            }
            dash = false;
        }
        return sb.toString();
    }

}
