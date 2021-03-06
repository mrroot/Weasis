/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.weasis.launcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.jar.JarOutputStream;
import java.util.jar.Pack200;
import java.util.jar.Pack200.Unpacker;
import java.util.zip.GZIPInputStream;

import org.apache.felix.framework.util.Util;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.service.startlevel.StartLevel;

public class AutoProcessor {

    /**
     * The property name used for the bundle directory.
     **/
    public static final String AUTO_DEPLOY_DIR_PROPERY = "felix.auto.deploy.dir"; //$NON-NLS-1$
    /**
     * The default name used for the bundle directory.
     **/
    public static final String AUTO_DEPLOY_DIR_VALUE = "bundle"; //$NON-NLS-1$
    /**
     * The property name used to specify auto-deploy actions.
     **/
    public static final String AUTO_DEPLOY_ACTION_PROPERY = "felix.auto.deploy.action"; //$NON-NLS-1$
    /**
     * The property name used to specify auto-deploy start level.
     **/
    public static final String AUTO_DEPLOY_STARTLEVEL_PROPERY = "felix.auto.deploy.startlevel"; //$NON-NLS-1$
    /**
     * The name used for the auto-deploy install action.
     **/
    public static final String AUTO_DEPLOY_INSTALL_VALUE = "install"; //$NON-NLS-1$
    /**
     * The name used for the auto-deploy start action.
     **/
    public static final String AUTO_DEPLOY_START_VALUE = "start"; //$NON-NLS-1$
    /**
     * The name used for the auto-deploy update action.
     **/
    public static final String AUTO_DEPLOY_UPDATE_VALUE = "update"; //$NON-NLS-1$
    /**
     * The name used for the auto-deploy uninstall action.
     **/
    public static final String AUTO_DEPLOY_UNINSTALL_VALUE = "uninstall"; //$NON-NLS-1$
    /**
     * The property name prefix for the launcher's auto-install property.
     **/
    public static final String AUTO_INSTALL_PROP = "felix.auto.install"; //$NON-NLS-1$
    /**
     * The property name prefix for the launcher's auto-start property.
     **/
    public static final String AUTO_START_PROP = "felix.auto.start"; //$NON-NLS-1$

    public static final String PACK200_COMPRESSION = ".pack.gz"; //$NON-NLS-1$

    /**
     * Used to instigate auto-deploy directory process and auto-install/auto-start configuration property processing
     * during.
     *
     * @param configMap
     *            Map of configuration properties.
     * @param context
     *            The system bundle context.
     * @param weasisLoader
     **/
    public static void process(Map configMap, BundleContext context, WeasisLoader weasisLoader) {
        configMap = (configMap == null) ? new HashMap() : configMap;
        processAutoDeploy(configMap, context, weasisLoader);
        processAutoProperties(configMap, context, weasisLoader);
    }

    /**
     * <p>
     * Processes bundles in the auto-deploy directory, performing the specified deploy actions.
     * </p>
     */
    private static void processAutoDeploy(Map configMap, BundleContext context, WeasisLoader weasisLoader) {
        // Determine if auto deploy actions to perform.
        String action = (String) configMap.get(AUTO_DEPLOY_ACTION_PROPERY);
        action = (action == null) ? "" : action; //$NON-NLS-1$
        List actionList = new ArrayList();
        StringTokenizer st = new StringTokenizer(action, ","); //$NON-NLS-1$
        while (st.hasMoreTokens()) {
            String s = st.nextToken().trim().toLowerCase();
            if (s.equals(AUTO_DEPLOY_INSTALL_VALUE) || s.equals(AUTO_DEPLOY_START_VALUE)
                || s.equals(AUTO_DEPLOY_UPDATE_VALUE) || s.equals(AUTO_DEPLOY_UNINSTALL_VALUE)) {
                actionList.add(s);
            }
        }

        // Perform auto-deploy actions.
        if (actionList.size() > 0) {
            // Retrieve the Start Level service, since it will be needed
            // to set the start level of the installed bundles.
            StartLevel sl = (StartLevel) context
                .getService(context.getServiceReference(org.osgi.service.startlevel.StartLevel.class.getName()));

            // Get start level for auto-deploy bundles.
            int startLevel = sl.getInitialBundleStartLevel();
            if (configMap.get(AUTO_DEPLOY_STARTLEVEL_PROPERY) != null) {
                try {
                    startLevel = Integer.parseInt(configMap.get(AUTO_DEPLOY_STARTLEVEL_PROPERY).toString());
                } catch (NumberFormatException ex) {
                    // Ignore and keep default level.
                }
            }

            // Get list of already installed bundles as a map.
            Map installedBundleMap = new HashMap();
            Bundle[] bundles = context.getBundles();
            for (int i = 0; i < bundles.length; i++) {
                installedBundleMap.put(bundles[i].getLocation(), bundles[i]);
            }

            // Get the auto deploy directory.
            String autoDir = (String) configMap.get(AUTO_DEPLOY_DIR_PROPERY);
            autoDir = (autoDir == null) ? AUTO_DEPLOY_DIR_VALUE : autoDir;
            // Look in the specified bundle directory to create a list
            // of all JAR files to install.
            File[] files = new File(autoDir).listFiles();
            List jarList = new ArrayList();
            if (files != null) {
                Arrays.sort(files);
                for (int i = 0; i < files.length; i++) {
                    if (files[i].getName().endsWith(".jar")) { //$NON-NLS-1$
                        jarList.add(files[i]);
                    }
                }
            }
            weasisLoader.setMax(jarList.size());
            // Install bundle JAR files and remember the bundle objects.
            final List startBundleList = new ArrayList();
            for (int i = 0; i < jarList.size(); i++) {
                // Look up the bundle by location, removing it from
                // the map of installed bundles so the remaining bundles
                // indicate which bundles may need to be uninstalled.
                File jar = (File) jarList.get(i);
                Bundle b = (Bundle) installedBundleMap.remove((jar).toURI().toString());
                try {
                    weasisLoader.writeLabel(WeasisLoader.LBL_DOWNLOADING + " " + jar.getName()); //$NON-NLS-1$

                    // If the bundle is not already installed, then install it
                    // if the 'install' action is present.
                    if ((b == null) && actionList.contains(AUTO_DEPLOY_INSTALL_VALUE)) {
                        b = installBundle(context, ((File) jarList.get(i)).toURI().toString());
                    }
                    // If the bundle is already installed, then update it
                    // if the 'update' action is present.
                    else if (b != null && actionList.contains(AUTO_DEPLOY_UPDATE_VALUE)) {
                        b.update();
                    }

                    // If we have found and/or successfully installed a bundle,
                    // then add it to the list of bundles to potentially start
                    // and also set its start level accordingly.
                    if (b != null) {
                        weasisLoader.setValue(i + 1);
                        if (!isFragment(b)) {
                            startBundleList.add(b);
                            sl.setBundleStartLevel(b, startLevel);
                        }
                    }

                } catch (Exception ex) {
                    System.err.println("Auto-deploy install: " + ex //$NON-NLS-1$
                        + ((ex.getCause() != null) ? " - " + ex.getCause() : "")); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }

            // Uninstall all bundles not in the auto-deploy directory if
            // the 'uninstall' action is present.
            if (actionList.contains(AUTO_DEPLOY_UNINSTALL_VALUE)) {
                for (Iterator it = installedBundleMap.entrySet().iterator(); it.hasNext();) {
                    Map.Entry entry = (Map.Entry) it.next();
                    Bundle b = (Bundle) entry.getValue();
                    if (b.getBundleId() != 0) {
                        try {
                            b.uninstall();
                        } catch (BundleException ex) {
                            printError(ex, "Auto-deploy uninstall: "); //$NON-NLS-1$
                        }
                    }
                }
            }

            // Start all installed and/or updated bundles if the 'start'
            // action is present.
            if (actionList.contains(AUTO_DEPLOY_START_VALUE)) {
                for (int i = 0; i < startBundleList.size(); i++) {
                    try {
                        ((Bundle) startBundleList.get(i)).start();

                    } catch (BundleException ex) {
                        printError(ex, "Auto-deploy start: "); //$NON-NLS-1$
                    }
                }
            }
        }
    }

    /**
     * <p>
     * Processes the auto-install and auto-start properties from the specified configuration properties.
     * </p>
     */
    private static void processAutoProperties(Map configMap, BundleContext context, WeasisLoader weasisLoader) {
        // Retrieve the Start Level service, since it will be needed
        // to set the start level of the installed bundles.
        StartLevel sl = (StartLevel) context
            .getService(context.getServiceReference(org.osgi.service.startlevel.StartLevel.class.getName()));

        // Retrieve all auto-install and auto-start properties and install
        // their associated bundles. The auto-install property specifies a
        // space-delimited list of bundle URLs to be automatically installed
        // into each new profile, while the auto-start property specifies
        // bundles to be installed and started. The start level to which the
        // bundles are assigned is specified by appending a ".n" to the
        // property name, where "n" is the desired start level for the list
        // of bundles. If no start level is specified, the default start
        // level is assumed.
        Map<String, BundleElement> bundleList = new HashMap<String, BundleElement>();

        Set set = configMap.keySet();
        for (Iterator item = set.iterator(); item.hasNext();) {
            String key = ((String) item.next()).toLowerCase();

            // Ignore all keys that are not an auto property.
            if (!key.startsWith(AUTO_INSTALL_PROP) && !key.startsWith(AUTO_START_PROP)) {
                continue;
            }
            // If the auto property does not have a start level,
            // then assume it is the default bundle start level, otherwise
            // parse the specified start level.
            int startLevel = sl.getInitialBundleStartLevel();
            try {
                startLevel = Integer.parseInt(key.substring(key.lastIndexOf('.') + 1));
            } catch (NumberFormatException ex) {
                System.err.println("Invalid start level: " + key); //$NON-NLS-1$
            }
            boolean canBeStarted = key.startsWith(AUTO_START_PROP);
            StringTokenizer st = new StringTokenizer((String) configMap.get(key), "\" ", true); //$NON-NLS-1$
            for (String location = nextLocation(st); location != null; location = nextLocation(st)) {
                String bundleName = getBundleNameFromLocation(location);
                if (!"System Bundle".equals(bundleName)) { //$NON-NLS-1$
                    BundleElement b = new BundleElement(startLevel, location, canBeStarted);
                    bundleList.put(bundleName, b);
                }
            }
        }
        weasisLoader.setMax(bundleList.size());

        final Map<String, Bundle> installedBundleMap = new HashMap<String, Bundle>();
        Bundle[] bundles = context.getBundles();
        for (int i = 0; i < bundles.length; i++) {
            String bundleName = getBundleNameFromLocation(bundles[i].getLocation());
            if (bundleName == null) {
                // Should never happen
                continue;
            }
            try {
                BundleElement b = bundleList.get(bundleName);
                // Remove the bundles in cache when they are not in the config.properties list
                if (b == null) {
                    if (!"System Bundle".equals(bundleName)) {//$NON-NLS-1$
                        bundles[i].uninstall();
                        System.out.println("Uninstall not used: " + bundleName); //$NON-NLS-1$
                    }
                    continue;
                }
                // Remove snapshot version to install it every time
                if (bundles[i].getVersion().getQualifier().endsWith("SNAPSHOT")) { //$NON-NLS-1$
                    bundles[i].uninstall();
                    System.out.println("Uninstall SNAPSHOT: " + bundleName); //$NON-NLS-1$
                    continue;
                }
                installedBundleMap.put(bundleName, bundles[i]);

            } catch (Exception e) {
                System.err.println("Cannot remove from OSGI cache: " + bundleName); //$NON-NLS-1$
            }
        }

        int bundleIter = 0;

        // Parse and install the bundles associated with the key.
        for (Iterator<Entry<String, BundleElement>> iter = bundleList.entrySet().iterator(); iter.hasNext();) {
            Entry<String, BundleElement> element = iter.next();
            String bundleName = element.getKey();
            BundleElement bundle = element.getValue();
            if (bundle == null) {
                // Should never happen
                continue;
            }
            try {
                weasisLoader.writeLabel(WeasisLoader.LBL_DOWNLOADING + " " + bundleName); //$NON-NLS-1$
                // Do not download again the same bundle version but with different location or already in installed
                // in cache from a previous version of Weasis
                Bundle b = installedBundleMap.get(bundleName);
                if (b == null) {
                    b = installBundle(context, bundle.getLocation());
                    installedBundleMap.put(bundleName, b);
                }
                sl.setBundleStartLevel(b, bundle.getStartLevel());
                loadTranslationBundle(context, b, installedBundleMap);
            } catch (Exception ex) {
                if (bundleName.contains(System.getProperty("native.library.spec"))) { //$NON-NLS-1$
                    System.err.println("Cannot install native bundle: " + bundleName); //$NON-NLS-1$
                } else {
                    printError(ex, "Cannot install bundle: " + bundleName); //$NON-NLS-1$
                    if (ex.getCause() != null) {
                        ex.printStackTrace();
                    }
                }
            } finally {
                bundleIter++;
                weasisLoader.setValue(bundleIter);
            }

        }

        weasisLoader.writeLabel(Messages.getString("AutoProcessor.start")); //$NON-NLS-1$
        // Now loop through the auto-start bundles and start them.
        for (Iterator<Entry<String, BundleElement>> iter = bundleList.entrySet().iterator(); iter.hasNext();) {
            Entry<String, BundleElement> element = iter.next();
            String bundleName = element.getKey();
            BundleElement bundle = element.getValue();
            if (bundle == null) {
                // Should never happen
                continue;
            }
            if (bundle.isCanBeStarted()) {
                try {
                    Bundle b = installedBundleMap.get(bundleName);
                    if (b == null) {
                        // Try to reinstall
                        b = installBundle(context, bundle.getLocation());
                    }
                    if (b != null) {
                        b.start();
                    }
                } catch (Exception ex) {
                    printError(ex, "Cannot start bundle: " + bundleName); //$NON-NLS-1$
                }
            }
        }
    }

    private static String getBundleNameFromLocation(String location) {
        if (location != null) {
            int index = location.lastIndexOf("/"); //$NON-NLS-1$
            String name = index >= 0 ? location.substring(index + 1) : location;
            index = name.lastIndexOf(".jar"); //$NON-NLS-1$
            return index >= 0 ? name.substring(0, index) : name;
        }
        return null;
    }

    private static void loadTranslationBundle(BundleContext context, Bundle b,
        final Map<String, Bundle> installedBundleMap) {
        if (WeasisLauncher.modulesi18n != null) {
            // Version v = b.getVersion();
            if (b != null) {
                StringBuilder p = new StringBuilder(b.getSymbolicName());
                p.append("-i18n-"); //$NON-NLS-1$
                // From 2.0.0, i18n module can be plugged in any version. The date (the qualifier)
                // will update the version.
                p.append("2.0.0"); //$NON-NLS-1$
                // p.append(v.getMajor());
                // p.append("."); //$NON-NLS-1$
                // p.append(v.getMinor());
                // p.append("."); //$NON-NLS-1$
                // p.append(v.getMicro());
                p.append(".jar"); //$NON-NLS-1$
                String filename = p.toString();
                String value = WeasisLauncher.modulesi18n.getProperty(filename);
                if (value != null) {
                    String baseURL = System.getProperty("weasis.i18n"); //$NON-NLS-1$
                    if (baseURL != null) {
                        String translation_modules = baseURL + (baseURL.endsWith("/") ? filename : "/" + filename); //$NON-NLS-1$ //$NON-NLS-2$
                        String bundleName = getBundleNameFromLocation(filename);
                        try {
                            Bundle b2 = installedBundleMap.get(bundleName);
                            if (b2 == null) {
                                b2 = context.installBundle(translation_modules, null);
                                installedBundleMap.put(bundleName, b);
                            }
                            if (b2 != null && !value.equals(b2.getVersion().getQualifier())) {
                                if (b2.getLocation().startsWith(baseURL)) {
                                    b2.update();
                                } else {
                                    // Handle same bundle version with different location
                                    try {
                                        b2.uninstall();
                                        context.installBundle(translation_modules, null);
                                        b2 = context.installBundle(translation_modules, null);
                                        installedBundleMap.put(bundleName, b);
                                    } catch (Exception exc) {
                                        System.err.println("Cannot install translation pack: " + translation_modules); //$NON-NLS-1$
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("Cannot install translation pack: " + translation_modules); //$NON-NLS-1$
                        }
                    }
                }
            }
        }
    }

    private static void printError(Exception ex, String prefix) {
        System.err.println(prefix + " (" + ex //$NON-NLS-1$
            + ((ex.getCause() != null) ? " - " + ex.getCause() : "") + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String nextLocation(StringTokenizer st) {
        String retVal = null;

        if (st.countTokens() > 0) {
            String tokenList = "\" "; //$NON-NLS-1$
            StringBuilder tokBuf = new StringBuilder(10);
            String tok = null;
            boolean inQuote = false;
            boolean tokStarted = false;
            boolean exit = false;
            while ((st.hasMoreTokens()) && (!exit)) {
                tok = st.nextToken(tokenList);
                if (tok.equals("\"")) { //$NON-NLS-1$
                    inQuote = !inQuote;
                    if (inQuote) {
                        tokenList = "\""; //$NON-NLS-1$
                    } else {
                        tokenList = "\" "; //$NON-NLS-1$
                    }

                } else if (tok.equals(" ")) { //$NON-NLS-1$
                    if (tokStarted) {
                        retVal = tokBuf.toString();
                        tokStarted = false;
                        tokBuf = new StringBuilder(10);
                        exit = true;
                    }
                } else {
                    tokStarted = true;
                    tokBuf.append(tok.trim());
                }
            }

            // Handle case where end of token stream and
            // still got data
            if ((!exit) && (tokStarted)) {
                retVal = tokBuf.toString();
            }
        }

        return retVal;
    }

    private static boolean isFragment(Bundle bundle) {
        return bundle.getHeaders().get(Constants.FRAGMENT_HOST) != null;
    }

    private static Bundle installBundle(BundleContext context, String location) throws Exception {
        boolean pack = location.endsWith(PACK200_COMPRESSION);
        if (pack) {
            // Remove the pack classifier from the location path
            location = location.substring(0, location.length() - 8);
            pack = context.getBundle(location) == null;
        }

        if (pack) {

            final URL url = new URL((URL) null, location + PACK200_COMPRESSION, null);

            // URLConnection conn = url.openConnection();
            // InputStream is = conn.getInputStream();
            // Unpacker unpacker = Pack200.newUnpacker();
            // File tmpFile = File.createTempFile("tmpPack200", ".jar");
            // JarOutputStream origJarStream = new JarOutputStream(new FileOutputStream(tmpFile));
            // unpacker.unpack(new GZIPInputStream(is), origJarStream);
            // origJarStream.close();

            final PipedInputStream in = new PipedInputStream();
            final PipedOutputStream out = new PipedOutputStream(in);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    JarOutputStream jarStream = null;
                    try {
                        URLConnection conn = url.openConnection();
                        // Support for http proxy authentication.
                        String auth = System.getProperty("http.proxyAuth", null); //$NON-NLS-1$
                        if ((auth != null) && (auth.length() > 0)) {
                            if ("http".equals(url.getProtocol()) || "https".equals(url.getProtocol())) { //$NON-NLS-1$ //$NON-NLS-2$
                                String base64 = Util.base64Encode(auth);
                                conn.setRequestProperty("Proxy-Authorization", "Basic " + base64); //$NON-NLS-1$ //$NON-NLS-2$
                            }
                        }
                        InputStream is = conn.getInputStream();
                        Unpacker unpacker = Pack200.newUnpacker();
                        jarStream = new JarOutputStream(out);
                        unpacker.unpack(new GZIPInputStream(is), jarStream);
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        FileUtil.safeClose(jarStream);
                    }
                }
            }).start();

            return context.installBundle(location, in);

        }
        return context.installBundle(location, null);
    }

    static class BundleElement {
        private final int startLevel;
        private final String location;
        private final boolean canBeStarted;

        public BundleElement(int startLevel, String location, boolean canBeStarted) {

            this.startLevel = startLevel;
            this.location = location;
            this.canBeStarted = canBeStarted;
        }

        public int getStartLevel() {
            return startLevel;
        }

        public String getLocation() {
            return location;
        }

        public boolean isCanBeStarted() {
            return canBeStarted;
        }

    }
}