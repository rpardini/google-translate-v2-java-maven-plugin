package com.nervepoint.maven.plugins;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import org.apache.commons.codec.binary.Hex;
import org.apache.maven.model.FileSet;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.DirectoryScanner;
import org.google.translate.api.v2.core.Translator;
import org.google.translate.api.v2.core.TranslatorException;
import org.google.translate.api.v2.core.model.Language;
import org.google.translate.api.v2.core.model.Translation;

/**
 * @goal translate
 * @requiresProject false
 * 
 * @author Lee David Painter
 * 
 */
public class GoogleTranslateV2 extends AbstractMojo {

    /**
     * @parameter expression="${api.key} default-value=""
     */
    private String apikey;

    /**
     * @parameter expression="${basedir}/src/main/resources"
     */
    private String sourceDirectory;

    /**
     * @parameter
     */
    private FileSet fileSet;

    /**
     * @parameter
     */
    boolean recurse;

    /**
     * @parameter expression="${basedir}/target/classes"
     *            default-value="${basedir}/target/classes"
     */
    private String targetDirectory;

    /**
     * @parameter expression="en" default-value="en"
     */
    private String sourceLanguage;

    /**
     * @parameter expression="es,fr,nl,it,pl,
     */
    private String languages;

    /**
     * @parameter default-value="${translateCacheDir}"
     */
    private String cacheDir;

    /**
     * @parameter
     */
    private String cacheTag;

    /**
     * @parameter
     */
    private List<String> noTranslatePattern = new ArrayList<String>();

    /**
     * @parameter default-value="true"
     */
    private boolean failOnMissingCacheDir;

    /**
     * @parameter default-value="false"
     */
    private boolean failOnMissingSourceDir;

    private File rootCacheDir;
    private List<Language> translateLanguages = new ArrayList<Language>();

    /**
     * @parameter default-value="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    private PatternReplacer replacer;

    public void execute() throws MojoExecutionException, MojoFailureException {

        if (apikey == null) {
            getLog().info("Translation will not be performed because there is no API key available");
        	return;
        }
        
        File masterCache;

        getLog().info("Cache dir is " + cacheDir);

        if (cacheDir == null || cacheDir.equals("${translateCacheDir}")) {
            getLog().info("Using default cache");
            masterCache = new File(System.getProperty("user.home"), ".i18n_cache");
        } else {
            getLog().info("Using user defined cache " + cacheDir);
            masterCache = new File(cacheDir);
        }

        rootCacheDir = new File(masterCache, project.getGroupId() + (cacheTag != null ? File.separator + cacheTag : ""));

        getLog().info("Master cache folder for this group/tag is " + rootCacheDir.getAbsolutePath());

        if (!rootCacheDir.exists() && failOnMissingCacheDir) {
            throw new MojoFailureException(
                            "Master cache folder is empty. This will result in full translation of all texts, either set failOnMissingCacheDir to false in plugin configuration, or create the folder to override this setting.");
        }

        rootCacheDir = new File(rootCacheDir, project.getArtifactId());

        getLog().info("Actual project cache is " + rootCacheDir.getAbsolutePath());

        rootCacheDir.mkdirs();

        replacer = new PatternReplacer();
        for (String p : noTranslatePattern) {
            getLog().info("Will not translate content matching " + p);
            replacer.addPattern(p);
        }

        Translator translator = new Translator(apikey);

        try {
            if (checkLanguages(translator)) {

                File source = new File(sourceDirectory);
                processDirectory(source, new File(targetDirectory), rootCacheDir, translator);
            } else {
                getLog().info("Nothing to do, no supported languages");
            }
        } catch (Exception e) {
            getLog().error(e);
            throw new MojoFailureException("Translate failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void processDirectory(File sourceDir, File destinationDir, File sourceCacheDir, Translator translator)
                    throws IOException, URISyntaxException, TranslatorException {

        getLog().info("Using source directory " + sourceDir.getAbsolutePath());
        getLog().info("Using target directory " + destinationDir.getAbsolutePath());

        destinationDir.mkdirs();

        if (!sourceDir.exists()) {

            if (failOnMissingSourceDir) {
                throw new IOException("sourceDirectory " + sourceDirectory
                                + " does not exist. To ignore this setting set failOnMissingSourceDir=false");
            }
            getLog().warn("sourceDirectory " + sourceDirectory + " does not exist");
            return;
        }

        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(sourceDir);
        if (fileSet == null || fileSet.getIncludes() == null || fileSet.getIncludes().size() == 0) {
            if (recurse) {
                scanner.setIncludes(new String[] { "*.properties" });
            } else {
                scanner.setIncludes(new String[] { "**/*.properties" });
            }
        } else {
            scanner.setIncludes((String[]) fileSet.getIncludes().toArray(new String[0]));
        }
        if (fileSet != null && fileSet.getExcludes() != null) {
            scanner.setExcludes((String[]) fileSet.getExcludes().toArray(new String[0]));
        }
        scanner.scan();
        String[] included = scanner.getIncludedFiles();
        getLog().info("Found " + included.length + " included files");

        for (String fileName : included) {
            File p = new File(sourceDir, fileName);
            if (p.isFile()) {
                int lidx = fileName.lastIndexOf('/');
                String dir = lidx == -1 ? "" : fileName.substring(0, lidx);

                int idx = p.getName().indexOf(".properties");

                String base = p.getName().substring(0, idx);
                String lang = "_" + sourceLanguage;
                if (base.endsWith(lang)) {
                    base = base.substring(0, base.length() - lang.length());
                }

                File dest = dir.equals("") ? destinationDir : new File(destinationDir, dir);
                File destCache = dir.equals("") ? sourceCacheDir : new File(sourceCacheDir, dir);

                getLog().info("    " + fileName + " -> " + dest.getAbsolutePath() + " [" + destCache.getAbsolutePath() + "]");

                translateFile(p, base, dest, destCache, translator);
            }
        }

    }

    private void translateFile(File sourceFile, String baseName, File desintationDir, File sourceCacheDir, Translator translator)
                    throws IOException, URISyntaxException, TranslatorException {

        for (Language l : translateLanguages) {

            if (baseName.endsWith("_" + l.getLanguage())) {
                getLog().info("Skipping " + baseName + ".properties as its an override file.");
                continue;
            }

            translateFileToLanguage(sourceFile, baseName, desintationDir, sourceCacheDir, l, translator);

        }

    }

    private void translateFileToLanguage(File sourceFile, String baseName, File destinationDir, File sourceCacheDir, Language l,
                                         Translator translator) throws IOException, URISyntaxException, TranslatorException {

        sourceCacheDir.mkdirs();

        getLog().info("Translating " + sourceFile.getName() + " to " + l.getName());

        File overrideFile = new File(sourceFile.getParentFile(), baseName + "_" + l.getLanguage() + ".properties");
        File previousTranslation = new File(sourceCacheDir, baseName + "_" + l.getLanguage() + ".properties");

        Properties p;
        Properties translated = new Properties();
        Properties override;
        Properties cached;

        p = loadProperties(sourceFile, "source");
        override = loadProperties(overrideFile, "override");
        cached = loadProperties(previousTranslation, "cache");

        boolean needCacheWrite = false;

        for (String name : p.stringPropertyNames()) {

            // The unprocessed content from the base resource file
            String originalContent = p.getProperty(name);

            /*
             * We process the source property for any patterns we don't want to
             * translate. These are sent to Google and the returned content is
             * processed again, putting the untranslatable text back where it
             * was.
             */
            String processed = replacer.preProcess(originalContent);

            if (override.containsKey(name)) {
                translated.put(name, override.getProperty(name));
                getLog().info("Detected overridden text for " + name);
                continue;
            } else if (cached.containsKey(name)) {

                String c = cached.getProperty(name);
                int idx = c.indexOf('|');
                String h = c.substring(0, idx);
                String text = c.substring(idx + 1);

                if (hash(processed).equals(h)) {
                    translated.put(name, replacer.postProcess(text));
                    continue;
                }
                getLog().info("Detected change to cached text for " + name);
            }

            getLog().info("Translating " + name);

            Translation t = translator.translate(processed, sourceLanguage, l.getLanguage());

            // And now the bit where the original untranslatable text is put
            // back
            String postProcessed = replacer.postProcess(t.getTranslatedText());

            translated.put(name, postProcessed);
            cached.put(name, hash(processed) + "|" + t.getTranslatedText());
            needCacheWrite = true;

        }

        File target = new File(destinationDir, baseName + "_" + l.getLanguage() + ".properties");

        if (target.exists()) {
            getLog().info("Deleting existing target " + target.getName() + " as we have a new translation.");
            target.delete();
        }

        FileOutputStream out = new FileOutputStream(target);
        try {
            translated.store(out, "Auto generated by Google Translate V2 API maven plugin");
        } finally {
            out.close();
        }

        if (needCacheWrite) {
            out = new FileOutputStream(previousTranslation);
            try {
                cached.store(out, "Cache of auto generated google translations for Google Translate V2 API maven plugin");
            } finally {
                out.close();
            }
        }

    }

    private String hash(String content) {
        try {
            MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            digest.update(content.getBytes("UTF-8"));
            byte[] hash = digest.digest();
            return Hex.encodeHexString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("");
        }
    }

    private Properties loadProperties(File path, String type) throws UnsupportedEncodingException, IOException {
        if (path.exists()) {
            getLog().info("Loading " + type + " file " + path.getAbsolutePath());
        }
        Properties p = new Properties();
        try {
            FileInputStream in = new FileInputStream(path);
            try {
                p.load(in);
            } finally {
                in.close();
            }
        } catch (FileNotFoundException ex) {
            if (type.equals("cache")) {
                getLog().warn("Could not find cache file " + path + " so a complete translation will be performed");
            }
        }

        return p;
    }

    private boolean checkLanguages(Translator translator) throws URISyntaxException, IOException, TranslatorException {

        Map<String, Language> tmp = new HashMap<String, Language>();

        Language[] langs = translator.languages(sourceLanguage);
        for (Language l : langs) {
            getLog().debug("Google Translate supports " + sourceLanguage + " to " + l.getLanguage() + " - " + l.getName());
            tmp.put(l.getLanguage().toLowerCase(), l);
        }

        StringTokenizer t = new StringTokenizer(languages, ",");
        while (t.hasMoreTokens()) {
            String lang = t.nextToken().toLowerCase();
            if (tmp.containsKey(lang)) {
                translateLanguages.add(tmp.get(lang));
            } else {
                getLog().warn(lang + " will not be supported as the translate API does not support it.");
            }
        }

        return translateLanguages.size() > 0;
    }

}