package com.bugsnag;

import com.bugsnag.callbacks.Callback;
import com.bugsnag.delivery.Delivery;
import com.bugsnag.logback.LogbackEndpoints;
import com.bugsnag.logback.LogbackMetaData;
import com.bugsnag.logback.LogbackMetaDataKey;
import com.bugsnag.logback.LogbackMetaDataTab;
import com.bugsnag.logback.ProxyConfiguration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;


/** Sends events to Bugsnag using its Java client library. */
public class BugsnagAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final String LOGGING_CONTEXT_THREAD_PREFIX
            = "com.bugsnag.BugsnagAppender.thread.";
    private static final String LOGGING_CONTEXT_REPORT_PREFIX
            = "com.bugsnag.BugsnagAppender.report.";
    private static final String LOGGING_CONTEXT_TAB_SEPARATOR = ".reportTab.";

    /** Classes that we should not send logs for (to prevent infinite loops on error) */
    private static final List<String> EXCLUDED_CLASSES = Arrays.asList(
            "com.bugsnag.Bugsnag",
            "com.bugsnag.delivery.OutputStreamDelivery",
            "com.bugsnag.delivery.SyncHttpDelivery");

    /** Object mapper to serialize into logging context with */
    private static ObjectMapper mapper = new ObjectMapper();

    /** Bugsnag API key; the appender doesn't do anything if it's not available. */
    private String apiKey;

    /** Whether or not to send unhandled exceptions to Bugsnag */
    private boolean sendUncaughtExceptions = true;

    /** Whether or not to automatically capture session information */
    private boolean autoCaptureSessions = true;

    /** Application type. */
    private String appType;

    /** Bugsnag error/session server endpoints. */
    private LogbackEndpoints logbackEndpoints;

    /** Property names that should be filtered out before sending to Bugsnag servers. */
    private Set<String> filteredProperties = new HashSet<String>();

    /** Exception classes to be ignored. */
    private Set<String> ignoredClasses = new HashSet<String>();

    /** Release stages that should be notified. */
    private Set<String> notifyReleaseStages = new HashSet<String>();

    /** Project packages. */
    private Set<String> projectPackages = new HashSet<String>();

    /** Proxy configuration to access the internet. */
    private ProxyConfiguration proxy;

    /** Release stage. */
    private String releaseStage;

    /** Whether thread state should be sent to Bugsnag. */
    private boolean sendThreads = false;

    /** Bugsnag API request timeout. */
    private int timeout;

    /** Application version. */
    private String appVersion;

    private List<LogbackMetaData> globalMetaData = new ArrayList<LogbackMetaData>();

    /** Bugsnag client. */
    private Bugsnag bugsnag = null;

    /** The appender instance */
    private static Map<String, BugsnagAppender> instances = new HashMap<String, BugsnagAppender>();

    /**
     * @return A running instance of the appender (if one has been created)
     */
    public static BugsnagAppender getInstance() {
        if (instances.size() == 0) {
            return null;
        } else  if (instances.size() == 1) {
            return instances.get(instances.keySet().toArray(new String[1])[0]);
        } else {
            throw new IllegalStateException(
                    "Multiple log appenders have been created, please supply API key parameter");
        }
    }

    /**
     * @param apiKey The API key of the appender to get (only required if using multiple API keys)
     * @return A running instance of the appender (if one has been created)
     */
    public static BugsnagAppender getInstance(String apiKey) {
        if (instances.containsKey(apiKey)) {
            return instances.get(apiKey);
        } else {
            return null;
        }
    }

    @Override
    public void start() {
        if (apiKey != null && !apiKey.isEmpty()) {
            this.bugsnag = createBugsnag();

            instances.put(apiKey, this);
        }
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        if (bugsnag != null) {
            bugsnag.close();
            instances.remove(apiKey);
        }
    }

    @Override
    protected void append(final ILoggingEvent event) {
        if (bugsnag != null) {
            Throwable throwable = extractThrowable(event);

            // Only send a message if there is an exception
            if (throwable != null && !detectLogFromBugsnag(throwable)) {
                bugsnag.notify(
                        throwable,
                        calculateSeverity(event),
                        new Callback() {
                            @Override
                            public void beforeNotify(Report report) {

                                // Add some data from the logging event
                                report.addToTab("Log event data",
                                        "Message", event.getMessage());
                                report.addToTab("Log event data",
                                        "Timestamp", event.getTimeStamp());

                                // Add details from the logging context to the event
                                populateContextData(report, event);
                            }
                        });
            }
        }
    }

    /**
     * Calculates the severity based on the logging event
     * @param event the event
     * @return The Bugsnag severity
     */
    private Severity calculateSeverity(ILoggingEvent event) {
        if (event.getLevel().equals(Level.ERROR)) {
            return Severity.ERROR;
        } else if (event.getLevel().equals(Level.WARN)) {
            return Severity.WARNING;
        }
        return Severity.INFO;
    }


    /**
     * Checks to see if a stack trace came from the Bugsnag library
     * (prevent possible infinite reporting loops)
     *
     * @param throwable the exception to check
     * @return true if the stacktrace contains a frame from the Bugsnag library
     */
    private boolean detectLogFromBugsnag(Throwable throwable) {
        // Check all places that LOGGER is called with an exception in the Bugsnag library
        for (StackTraceElement element : throwable.getStackTrace()) {
            for (String excludedClass : EXCLUDED_CLASSES) {
                if (element.getClassName().startsWith(excludedClass)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Gets the throwable from the log event (if any)
     * @param event The log event
     * @return The throwable (or null)
     */
    private Throwable extractThrowable(ILoggingEvent event) {
        IThrowableProxy throwableProxy = event.getThrowableProxy();
        if (throwableProxy instanceof ThrowableProxy) {
            return ((ThrowableProxy) throwableProxy).getThrowable();
        }

        return null;
    }

    /**
     * @return Create a Bugsnag instance with parameters from the logback configuration
     */
    private Bugsnag createBugsnag() {
        Bugsnag bugsnag = Bugsnag.init(apiKey, sendUncaughtExceptions);

        bugsnag.setAutoCaptureSessions(autoCaptureSessions);

        if (appType != null) {
            bugsnag.setAppType(appType);
        }

        if (appVersion != null) {
            bugsnag.setAppVersion(appVersion);
        }

        if (logbackEndpoints != null) {
            bugsnag.setEndpoints(logbackEndpoints.getNotifyEndpoint(),
                    logbackEndpoints.getSessionEndpoint());
        }

        if (proxy != null) {
            bugsnag.setProxy(
                    new Proxy(
                            proxy.getType(),
                            new InetSocketAddress(proxy.getHostname(), proxy.getPort())));
        }

        if (releaseStage != null) {
            bugsnag.setReleaseStage(releaseStage);
        }

        if (timeout > 0) {
            bugsnag.setTimeout(timeout);
        }

        if (filteredProperties.size() > 0) {
            bugsnag.setFilters(filteredProperties.toArray(new String[0]));
        }

        bugsnag.setIgnoreClasses(ignoredClasses.toArray(new String[0]));

        if (notifyReleaseStages.size() > 0) {
            bugsnag.setNotifyReleaseStages(notifyReleaseStages.toArray(new String[0]));
        }
        bugsnag.setProjectPackages(projectPackages.toArray(new String[0]));
        bugsnag.setSendThreads(sendThreads);

        // Add a callback to put global meta data on every report
        bugsnag.addCallback(new Callback() {
            @Override
            public void beforeNotify(Report report) {

                for (int i = 0; i < globalMetaData.size(); i++) {
                    LogbackMetaData metaData = globalMetaData.get(i);

                    for (LogbackMetaDataTab tab : metaData.getTabs()) {
                        for (LogbackMetaDataKey key : tab.getKeys()) {
                            report.addToTab(tab.getName(),
                                    key.getName(),
                                    key.getValue());
                        }
                    }

                }
            }
        });

        return bugsnag;
    }

    /**
     * Adds the given key / value to the current thread logging context
     *
     * @param key the key to add
     * @param value the value to add
     */
    public static void addThreadMetaData(String tab, String key, Object value) {
        MDC.put(LOGGING_CONTEXT_THREAD_PREFIX + tab
                + LOGGING_CONTEXT_TAB_SEPARATOR + key, getStringValue(value));
    }

    /**
     * Adds the given key / value to the current thread logging context
     * Will only be used for the next report, and then removed from the context
     *
     * @param key the key to add
     * @param value the value to add
     */
    public static void addReportMetaData(String tab, String key, Object value) {
        MDC.put(LOGGING_CONTEXT_REPORT_PREFIX + tab
                + LOGGING_CONTEXT_TAB_SEPARATOR + key, getStringValue(value));
    }

    /**
     * Clears all meta data added to the current thread
     */
    public static void clearThreadMetaData() {
        if (MDC.getMDCAdapter() != null) {
            Map<String, String> context = MDC.getCopyOfContextMap();

            if (context != null) {
                Iterator<String> iterator = context.keySet().iterator();

                // Loop over the keys and remove the thread ones
                while (iterator.hasNext()) {
                    String key = iterator.next();

                    if (key.startsWith(LOGGING_CONTEXT_THREAD_PREFIX)) {
                        MDC.remove(key);
                    }
                }
            }
        }
    }

    /**
     * Adds thread logging context values to the given report meta data
     *
     * @param report The report being sent to Bugsnag
     * @param event The values in the logging context
     */
    private void populateContextData(Report report, ILoggingEvent event) {
        if (event.getMDCPropertyMap() != null) {
            Iterator<String> iterator = event.getMDCPropertyMap().keySet().iterator();

            // Loop through all the keys and put them in the correct tabs
            while (iterator.hasNext()) {
                String key = iterator.next();

                if (key.startsWith(LOGGING_CONTEXT_REPORT_PREFIX)) {
                    populateKey(key,
                            event.getMDCPropertyMap().get(key),
                            LOGGING_CONTEXT_REPORT_PREFIX,
                            report);
                    iterator.remove();
                } else if (key.startsWith(LOGGING_CONTEXT_THREAD_PREFIX)) {
                    populateKey(key,
                            event.getMDCPropertyMap().get(key),
                            LOGGING_CONTEXT_THREAD_PREFIX,
                            report);
                }
            }
        }
    }

    /**
     * Adds the given key/value to the report
     *
     * @param key    The key to add
     * @param value  The value to add
     * @param prefix The prefix of the key
     * @param report The report to add the value to
     */
    private void populateKey(String key, String value, String prefix, Report report) {
        if (key.contains(LOGGING_CONTEXT_TAB_SEPARATOR)) {
            String[] parts = key
                    .substring(prefix.length())
                    .split(LOGGING_CONTEXT_TAB_SEPARATOR);

            report.addToTab(parts[0], parts[1], getObjectValue(value));
        } else {
            report.addToTab("Context Data", key, getObjectValue(value));
        }
    }

    /**
     * Serializes the contents of the value if required
     *
     * @param value The value to serialize
     * @return The value as a string
     */
    private static String getStringValue(Object value) {
        if (value instanceof  String) {
            return (String)value;
        } else {
            try {
                return mapper.writeValueAsString(value);
            } catch (JsonProcessingException exception) {
                return value.toString();
            }
        }
    }

    /**
     * Deserializes the contents of the value
     *
     * @param value The value to deserialize
     * @return The value as an object
     */
    private static Object getObjectValue(String value) {
        try {
            if (value.startsWith("{")) {
                return mapper.readValue(value, Map.class);
            } else if (value.startsWith("[")) {
                return mapper.readValue(value, List.class);
            } else {
                return value;
            }
        } catch (IOException exception) {
            // Just return the raw string if it could not be read
            return value;
        }
    }

    /**
     * Add a callback to execute code before/after every notification to Bugsnag.
     *
     * <p>You can use this to add or modify information attached to an error
     * before it is sent to your dashboard. You can also stop any reports being
     * sent to Bugsnag completely.
     *
     * @param callback a callback to run before sending errors to Bugsnag
     * @see Callback
     */
    public void addCallback(Callback callback) {
        if (bugsnag != null) {
            bugsnag.addCallback(callback);
        }
    }

    /**
     * Manually starts tracking a new session.
     *
     * Note: sessions are currently tracked on a per-thread basis. Therefore, if this method were
     * called from Thread A then Thread B, two sessions would be considered 'active'. Any custom
     * strategy used to track sessions should take this into account.
     *
     * Automatic session tracking can be enabled via
     * {@link BugsnagAppender#setAutoCaptureSessions(boolean)}, which will automatically
     * create a new session for each request
     */
    public void startSession() {
        if (bugsnag != null) {
            bugsnag.startSession();
        }
    }

    /**
     * Set the method of delivery for Bugsnag error report. By default we'll
     * send reports asynchronously using a thread pool to
     * https://notify.bugsnag.com, but you can override this to use a
     * different sending technique or endpoint (for example, if you are using
     * Bugsnag On-Premise).
     *
     * @param delivery the delivery mechanism to use
     * @see Delivery
     */
    public void setDelivery(Delivery delivery) {
        if (bugsnag != null) {
            bugsnag.setDelivery(delivery);
        }
    }

    /**
     * Set the method of delivery for Bugsnag sessions. By default we'll
     * send sessions asynchronously using a thread pool to
     * https://sessions.bugsnag.com, but you can override this to use a
     * different sending technique or endpoint (for example, if you are using
     * Bugsnag On-Premise).
     *
     * @param delivery the delivery mechanism to use
     * @see Delivery
     */
    public void setSessionDelivery(Delivery delivery) {
        if (bugsnag != null) {
            bugsnag.setSessionDelivery(delivery);
        }
    }

    // Setters

    /**
     * Internal use only
     * Should only be used via the logback.xml file
     *
     * @param apiKey The API key to use
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Internal use only
     * Should only be used via the logback.xml file
     *
     * @param sendUncaughtExceptions Whether or not Bugsnag should catch unhandled exceptions
     */
    public void setSendUncaughtExceptions(boolean sendUncaughtExceptions) {
        this.sendUncaughtExceptions = sendUncaughtExceptions;
    }

    /**
     * @see Bugsnag#setAutoCaptureSessions(boolean)
     */
    public void setAutoCaptureSessions(boolean autoCaptureSessions) {
        this.autoCaptureSessions = autoCaptureSessions;

        if (bugsnag != null) {
            bugsnag.setAutoCaptureSessions(autoCaptureSessions);
        }
    }

    /**
     * @see Bugsnag#setAppType(String)
     */
    public void setAppType(String appType) {
        this.appType = appType;

        if (bugsnag != null) {
            bugsnag.setAppType(appType);
        }
    }

    /**
     * Internal use only
     * Should only be used via the logback.xml file
     *
     * @see Bugsnag#setEndpoints(String, String)
     */
    public void setEndpoints(LogbackEndpoints logbackEndpoints) {
        this.logbackEndpoints = logbackEndpoints;

        if (bugsnag != null) {
            bugsnag.setEndpoints(logbackEndpoints.getNotifyEndpoint(),
                    logbackEndpoints.getSessionEndpoint());
        }
    }

    /**
     * @see Bugsnag#setFilters(String...)
     */
    public void setFilteredProperty(String filter) {
        this.filteredProperties.add(filter);

        if (bugsnag != null) {
            bugsnag.setFilters(this.filteredProperties.toArray(new String[0]));
        }
    }

    /**
     * @see Bugsnag#setFilters(String...)
     */
    public void setFilteredProperties(String filters) {
        this.filteredProperties.addAll(split(filters));

        if (bugsnag != null) {
            bugsnag.setFilters(this.filteredProperties.toArray(new String[0]));
        }
    }

    /**
     * @see Bugsnag#setIgnoreClasses(String...)
     */
    public void setIgnoredClass(String ignoredClass) {
        this.ignoredClasses.add(ignoredClass);

        if (bugsnag != null) {
            bugsnag.setIgnoreClasses(this.ignoredClasses.toArray(new String[0]));
        }
    }

    /**
     * @see Bugsnag#setIgnoreClasses(String...)
     */
    public void setIgnoredClasses(String ignoredClasses) {
        this.ignoredClasses.addAll(split(ignoredClasses));

        if (bugsnag != null) {
            bugsnag.setIgnoreClasses(this.ignoredClasses.toArray(new String[0]));
        }
    }

    /**
     * @see Bugsnag#setNotifyReleaseStages(String...)
     */
    public void setNotifyReleaseStage(String notifyReleaseStage) {
        this.notifyReleaseStages.add(notifyReleaseStage);

        if (bugsnag != null) {
            bugsnag.setNotifyReleaseStages(this.notifyReleaseStages.toArray(new String[0]));
        }
    }

    /**
     * @see Bugsnag#setNotifyReleaseStages(String...)
     */
    public void setNotifyReleaseStages(String notifyReleaseStages) {
        this.notifyReleaseStages.addAll(split(notifyReleaseStages));

        if (bugsnag != null) {
            bugsnag.setNotifyReleaseStages(this.notifyReleaseStages.toArray(new String[0]));
        }
    }

    /**
     * @see Bugsnag#setProjectPackages(String...)
     */
    public void setProjectPackage(String projectPackage) {
        this.projectPackages.add(projectPackage);

        if (bugsnag != null) {
            bugsnag.setProjectPackages(this.projectPackages.toArray(new String[0]));
        }
    }


    /**
     * @see Bugsnag#setProjectPackages(String...)
     */
    public void setProjectPackages(String projectPackages) {
        this.projectPackages.addAll(split(projectPackages));

        if (bugsnag != null) {
            bugsnag.setProjectPackages(this.projectPackages.toArray(new String[0]));
        }
    }

    /**
     * @see Bugsnag#setProxy(Proxy)
     */
    public void setProxy(ProxyConfiguration proxy) {
        this.proxy = proxy;

        if (bugsnag != null) {
            bugsnag.setProxy(
                    new Proxy(
                            proxy.getType(),
                            new InetSocketAddress(proxy.getHostname(), proxy.getPort())));
        }
    }

    /**
     * @see Bugsnag#setReleaseStage(String)
     */
    public void setReleaseStage(String releaseStage) {
        this.releaseStage = releaseStage;

        if (bugsnag != null) {
            bugsnag.setReleaseStage(releaseStage);
        }
    }

    /**
     * @see Bugsnag#setSendThreads(boolean)
     */
    public void setSendThreads(boolean sendThreads) {
        this.sendThreads = sendThreads;

        if (bugsnag != null) {
            bugsnag.setSendThreads(sendThreads);
        }
    }

    /**
     * @see Bugsnag#setTimeout(int)
     */
    public void setTimeout(int timeout) {
        this.timeout = timeout;

        if (bugsnag != null) {
            bugsnag.setTimeout(timeout);
        }
    }

    /**
     * @see Bugsnag#setAppVersion(String)
     */
    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;

        if (bugsnag != null) {
            bugsnag.setAppVersion(appVersion);
        }
    }

    /**
     * Used to read metadata from the logback.xml file
     *
     * @param metaData Adds meta data to every report
     */
    public void setMetaData(LogbackMetaData metaData) {
        this.globalMetaData.add(metaData);
    }

    /**
     * Splits the given string on commas
     * @param value The string to split
     * @return The list of parts
     */
    private List<String> split(String value) {
        String[] parts = value.split(",", -1);
        return Arrays.asList(parts);
    }

    /**
     * @return The Bugsnag instance (used internally only)
     */
    Bugsnag getBugsnag() {
        return bugsnag;
    }
}