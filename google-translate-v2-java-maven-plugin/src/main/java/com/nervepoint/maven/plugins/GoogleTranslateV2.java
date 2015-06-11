package com.nervepoint.maven.plugins;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.translate.Translate;
import com.google.api.services.translate.TranslateRequestInitializer;
import com.google.api.services.translate.model.TranslationsListResponse;
import com.google.api.services.translate.model.TranslationsResource;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.FileSet;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.DirectoryScanner;

import java.io.*;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Lee David Painter
 * @author Brett Smith
 * @goal translate
 * @requiresProject false
 */
public class GoogleTranslateV2 extends AbstractMojo {

    private final JsonFactory JSON_FACTORY = JacksonFactory
            .getDefaultInstance();

    private HttpTransport httpTransport;

    private static Translate client;

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
     * default-value="${basedir}/target/classes"
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

    /**
     * @parameter default-value="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    private PatternReplacer replacer;

    public void execute() throws MojoExecutionException, MojoFailureException {

        if (apikey == null) {
            getLog().info(
                    "Translation will not be performed because there is no API key available");
            return;
        }

        File masterCache;

        getLog().info("Cache dir is " + cacheDir);

        if (cacheDir == null || cacheDir.equals("${translateCacheDir}")) {
            getLog().info("Using default cache");
            masterCache = new File(System.getProperty("user.home"),
                    ".i18n_cache");
        } else {
            getLog().info("Using user defined cache " + cacheDir);
            masterCache = new File(cacheDir);
        }

        rootCacheDir = new File(masterCache, project.getGroupId()
                + (cacheTag != null ? File.separator + cacheTag : ""));

        getLog().info(
                "Master cache folder for this group/tag is "
                        + rootCacheDir.getAbsolutePath());

        if (!rootCacheDir.exists() && failOnMissingCacheDir) {
            throw new MojoFailureException(
                    "Master cache folder is empty. This will result in full translation of all texts, either set failOnMissingCacheDir to false in plugin configuration, or create the folder to override this setting.");
        }

        rootCacheDir = new File(rootCacheDir, project.getArtifactId());

        getLog().info(
                "Actual project cache is " + rootCacheDir.getAbsolutePath());

        rootCacheDir.mkdirs();

        replacer = new PatternReplacer();
        for (String p : noTranslatePattern) {
            getLog().info("Will not translate content matching " + p);
            replacer.addPattern(p);
        }

        try {
            // initialize the transport
            httpTransport = GoogleNetHttpTransport.newTrustedTransport();

            // set up global Translate instance
            client = new Translate.Builder(httpTransport, JSON_FACTORY, null)
                    .setGoogleClientRequestInitializer(
                            new TranslateRequestInitializer(apikey))
                    .setApplicationName("GoogleTranslateMavenPlugin/0.2")
                    .build();

            try {

                File source = new File(sourceDirectory);
                processDirectory(source, new File(targetDirectory),
                        rootCacheDir);
            } catch (Exception e) {
                getLog().error(e);
                throw new MojoFailureException("Translate failed: "
                        + e.getMessage());
            }

            return;
        } catch (IOException e) {
            getLog().error(e);
        } catch (Throwable t) {
            getLog().error(t);
        }

        throw new MojoFailureException("Translation failed due ot previous exceptions");

    }

    @SuppressWarnings("unchecked")
    private void processDirectory(File sourceDir, File destinationDir,
                                  File sourceCacheDir) throws IOException, URISyntaxException {

        getLog().info("Using source directory " + sourceDir.getAbsolutePath());
        getLog().info(
                "Using target directory " + destinationDir.getAbsolutePath());

        destinationDir.mkdirs();

        if (!sourceDir.exists()) {

            if (failOnMissingSourceDir) {
                throw new IOException(
                        "sourceDirectory "
                                + sourceDirectory
                                + " does not exist. To ignore this setting set failOnMissingSourceDir=false");
            }
            getLog().warn(
                    "sourceDirectory " + sourceDirectory + " does not exist");
            return;
        }

        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(sourceDir);
        if (fileSet == null || fileSet.getIncludes() == null
                || fileSet.getIncludes().size() == 0) {
            if (recurse) {
                scanner.setIncludes(new String[]{"*_" + sourceLanguage + ".properties"});
            } else {
                scanner.setIncludes(new String[]{"**/*" + sourceLanguage + ".properties"});
            }
        } else {
            scanner.setIncludes((String[]) fileSet.getIncludes().toArray(
                    new String[0]));
        }
        if (fileSet != null && fileSet.getExcludes() != null) {
            scanner.setExcludes((String[]) fileSet.getExcludes().toArray(
                    new String[0]));
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

                File dest = dir.equals("") ? destinationDir : new File(
                        destinationDir, dir);
                File destCache = dir.equals("") ? sourceCacheDir : new File(
                        sourceCacheDir, dir);

                getLog().info(
                        "    " + fileName + " -> " + dest.getAbsolutePath()
                                + " [" + destCache.getAbsolutePath() + "]");

                translateFile(p, base, dest, destCache);
            }
        }

    }

    private void translateFile(File sourceFile, String baseName,
                               File desintationDir, File sourceCacheDir) throws IOException,
            URISyntaxException {

        StringTokenizer t = new StringTokenizer(languages, ",");
        while (t.hasMoreTokens()) {

            String l = t.nextToken();

            if (baseName.endsWith("_" + l)) {
                getLog().info(
                        "Skipping " + baseName
                                + ".properties as its an override file.");
                continue;
            }

            translateFileToLanguage(sourceFile, baseName, desintationDir,
                    sourceCacheDir, l);

        }

    }

    private void translateFileToLanguage(File sourceFile, String baseName,
                                         File destinationDir, File sourceCacheDir, String language)
            throws IOException, URISyntaxException {

        sourceCacheDir.mkdirs();

        getLog().info("Translating " + sourceFile.getName() + " to " + language);

        File overrideFile = new File(sourceFile.getParentFile(), baseName + "_"
                + language + ".properties");
        File previousTranslation = new File(sourceCacheDir, baseName + "_"
                + language + ".properties");

        Properties p;
        Properties translated = new Properties();
        Properties override;
        Properties cached;

        p = loadProperties(sourceFile, "source");
        override = loadProperties(overrideFile, "override");
        cached = loadProperties(previousTranslation, "cache");

        boolean needCacheWrite = false;

//		List<String> toTranslateValues = new ArrayList<String>();
//		List<String> toTranslateKeys = new ArrayList<String>();
//		for (String name : p.stringPropertyNames()) {
//
//			// The unprocessed content from the base resource file
//			String originalContent = p.getProperty(name);
//
//			/*
//			 * We process the source property for any patterns we don't want to
//			 * translate. These are sent to Google and the returned content is
//			 * processed again, putting the untranslatable text back where it
//			 * was.
//			 */
//			String processed = replacer.preProcess(originalContent);
//
//			if (override.containsKey(name)) {
//				translated.put(name, override.getProperty(name));
//				getLog().info("Detected overridden text for " + name);
//				continue;
//			} else if (cached.containsKey(name)) {
//
//				String c = cached.getProperty(name);
//				int idx = c.indexOf('|');
//				String h = c.substring(0, idx);
//				String text = c.substring(idx + 1);
//
//				if (hash(processed).equals(h)) {
//					translated.put(name, replacer.postProcess(text));
//					continue;
//				}
//				getLog().info("Detected change to cached text for " + name);
//			}
//
//			getLog().info("Translating " + name);
//
//			toTranslateKeys.add(name);
//			toTranslateValues.add(processed);
//			
//			String translation = translate(processed, sourceLanguage, language);
//
//			
//			// And now the bit where the original untranslatable text is put
//			// back
//			String postProcessed = replacer.postProcess(translation);
//
//			translated.put(name, postProcessed);
//			cached.put(name, hash(processed) + "|" + translation);
//			needCacheWrite = true;
//
//		}
//
//		File target = new File(destinationDir, baseName + "_" + language
//				+ ".properties");
//
//		if (target.exists()) {
//			getLog().info(
//					"Deleting existing target " + target.getName()
//							+ " as we have a new translation.");
//			target.delete();
//		}
//
//		FileOutputStream out = new FileOutputStream(target);
//		try {
//			translated.store(out,
//					"Auto generated by Google Translate V2 API maven plugin");
//		} finally {
//			out.close();
//		}
//
//		if (needCacheWrite) {
//			out = new FileOutputStream(previousTranslation);
//			try {
//				cached.store(
//						out,
//						"Cache of auto generated google translations for Google Translate V2 API maven plugin");
//			} finally {
//				out.close();
//			}
//		}
//		
        /**
         * The section below is more efficient, performing multiple translations in
         * each API call, but there are limits to the number of texts we can send in a
         * single call, thus, this will need improving before we can use it.
         */
        List<String> toTranslateValues = new ArrayList<String>();
        List<String> toTranslateKeys = new ArrayList<String>();
        int characters = 0;
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

            /*if (override.containsKey(name)) {
                translated.put(name, override.getProperty(name));
                getLog().info("Detected overridden text for " + name);
                continue;
            } else */
            if (cached.containsKey(name)) {

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

            getLog().debug("Marking " + name + " for translation");

            toTranslateKeys.add(name);
            toTranslateValues.add(processed);

            characters += processed.length();

            if (characters > 4000) {

                getLog().info("Translating " + characters + " characters");
                List<TranslationsResource> translations = translate(toTranslateValues, sourceLanguage, language);


                for (TranslationsResource t : translations) {
                    // And now the bit where the original untranslatable text is put
                    // back
                    String postProcessed = replacer.postProcess(t.getTranslatedText());

                    name = toTranslateKeys.remove(0);
                    processed = toTranslateValues.remove(0);

                    translated.put(name, postProcessed);
                    cached.put(name, hash(processed) + "|" + t.getTranslatedText());
                    needCacheWrite = true;
                }

                characters = 0;
            }
        }

        if (characters > 0) {

            getLog().info("Translating " + characters + " characters [final translation for this module]");

            List<TranslationsResource> translations = translate(toTranslateValues, sourceLanguage, language);


            for (TranslationsResource t : translations) {
                // And now the bit where the original untranslatable text is put
                // back
                String postProcessed = replacer.postProcess(t.getTranslatedText());

                String name = toTranslateKeys.remove(0);
                String processed = toTranslateValues.remove(0);

                translated.put(name, postProcessed);
                cached.put(name, hash(processed) + "|" + t.getTranslatedText());
                needCacheWrite = true;
            }

            characters = 0;
        }

        File target = new File(destinationDir, baseName + "_" + language
                + ".properties");

        if (target.exists()) {
            getLog().info(
                    "Deleting existing target " + target.getName()
                            + " as we have a new translation.");
            target.delete();
        }

        FileOutputStream out = new FileOutputStream(target);
        try {
            translated.store(out,
                    "Auto generated by Google Translate V2 API maven plugin");
        } finally {
            out.close();
        }

        if (needCacheWrite) {
            out = new FileOutputStream(previousTranslation);
            try {
                cached.store(
                        out,
                        "Cache of auto generated google translations for Google Translate V2 API maven plugin");
            } finally {
                out.close();
            }
        }


    }

    List<TranslationsResource> translate(List<String> sources, String sourceLang, String targetLang) throws IOException {

        if (false) {
            Translate.Translations.List res = client.translations().list(sources,
                    targetLang);
            res.setSource(sourceLang);
            TranslationsListResponse c = res.execute();

            return c.getTranslations();

        } else {


            ArrayList<TranslationsResource> translationsResources = new ArrayList<TranslationsResource>();

            for (String sourceString : sources) {

                TranslationsResource trans = new TranslationsResource();
                trans.setTranslatedText(replaceForKlingonChars(sourceString));
                translationsResources.add(trans);


            }


            return translationsResources;


        }
    }

    /**
     * This takes a message and replace the characters for funny klingon chars acorrding to defined tokens.
     *
     * @param s
     * @return A klingonized message
     */
    private String replaceForKlingonChars(String s) {
        Map<String, String> tokens = getTokensMap();

        String patternString = "(" + StringUtils.join(tokens.keySet(), "|") + ")";
        Pattern pattern = Pattern.compile(patternString);
        Matcher matcher = pattern.matcher(s);

        StringBuffer sb = new StringBuffer();
        String klingonizedMessage;

        while (matcher.find()) {
            matcher.appendReplacement(sb, tokens.get(matcher.group(1)));
        }
        matcher.appendTail(sb);
        klingonizedMessage = unKlingonizeMissedChars(sb.toString());

        return klingonizedMessage;
    }

    /**
     * Okay klingon messages are funny, but some times we mess the message up by klingonizing characters that we
     * don't want. The solution is unklingozine the knowing characters.
     * Example: "Hello {0}", klingon: "H3ll0 {Ó}", correct: "H3ll0 {0}"
     *
     * @param s
     * @return The unklingonized characters message
     */
    private String unKlingonizeMissedChars(String s) {

        Map<String, String> tokens = new HashMap<String, String>();
        tokens.put("A", "{4}");
        tokens.put("Ê", "{3}");
        tokens.put("î", "{1}");
        tokens.put("Ó", "{0}");

        String patternString = "\\{(" + StringUtils.join(tokens.keySet(), "|") + ")\\}";
        Pattern pattern = Pattern.compile(patternString);
        Matcher matcher = pattern.matcher(s);

        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            matcher.appendReplacement(sb, tokens.get(matcher.group(1)));
        }
        matcher.appendTail(sb);
        return sb.toString();

    }

    private Map<String, String> getTokensMap() {
        Map<String, String> tokens = new HashMap<String, String>();
        tokens.put("A", "4");
        tokens.put("a", "4");
        tokens.put("E", "3");
        tokens.put("e", "3");
        tokens.put("I", "1");
        tokens.put("i", "1");
        tokens.put("O", "0");
        tokens.put("o", "0");
        tokens.put("U", "û");
        tokens.put("u", "û");
        tokens.put("M", "m");
        tokens.put("N", "Ñ");
        tokens.put("n", "ñ");
        tokens.put("C", "Ç");
        tokens.put("c", "ç");
        tokens.put("4", "A");
        tokens.put("3", "Ê");
        tokens.put("1", "î");
        tokens.put("0", "Ó");
        return tokens;
    }


    private String hash(String content) {
        try {
            MessageDigest digest = java.security.MessageDigest
                    .getInstance("MD5");
            digest.update(content.getBytes("UTF-8"));
            byte[] hash = digest.digest();
            return Hex.encodeHexString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("");
        }
    }

    private Properties loadProperties(File path, String type)
            throws UnsupportedEncodingException, IOException {
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
                getLog().warn(
                        "Could not find cache file "
                                + path
                                + " so a complete translation will be performed");
            }
        }

        return p;
    }

}
