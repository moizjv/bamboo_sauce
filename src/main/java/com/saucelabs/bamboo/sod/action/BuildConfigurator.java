package com.saucelabs.bamboo.sod.action;

import com.atlassian.bamboo.build.CustomPreBuildAction;
import com.atlassian.bamboo.configuration.AdministrationConfiguration;
import com.atlassian.bamboo.configuration.AdministrationConfigurationManager;
import com.atlassian.bamboo.plan.Plan;
import com.atlassian.bamboo.v2.build.BaseConfigurableBuildPlugin;
import com.atlassian.bamboo.v2.build.BuildContext;
import com.atlassian.bamboo.ww2.actions.build.admin.create.BuildConfiguration;
import com.saucelabs.bamboo.sod.Browser;
import com.saucelabs.bamboo.sod.BrowserFactory;
import com.saucelabs.bamboo.sod.SeleniumVersion;
import com.saucelabs.bamboo.sod.config.SODKeys;
import com.saucelabs.bamboo.sod.config.SODMappedBuildConfiguration;
import com.saucelabs.bamboo.sod.util.SauceConnectTwoManager;
import com.saucelabs.bamboo.sod.util.SauceFactory;
import com.saucelabs.bamboo.sod.util.SauceTunnelManager;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Pre-Build Action which will start a SSH Tunnel via the Sauce REST API if the build is configured to run
 * Selenium tests via the Sauce Connect tunnel.
 *
 * @author <a href="http://www.sysbliss.com">Jonathan Doklovic</a>
 * @author Ross Rowe
 */
public class BuildConfigurator extends BaseConfigurableBuildPlugin implements CustomPreBuildAction {
    
    private static final Logger logger = Logger.getLogger(BuildConfigurator.class);

    /**
     * Populated via dependency injection.
     */
    private SauceTunnelManager sauceTunnelManager;

    /**
     * Populated by dependency injection.
     */
    private SauceFactory sauceAPIFactory;

    /**
     * Populated via dependency injection.
     */
    private AdministrationConfigurationManager administrationConfigurationManager;
    /**
     * Populated via dependency injection.
     */
    private BrowserFactory sauceBrowserFactory;

    private static final Browser DEFAULT_BROWSER = new Browser("unknown", "unknown", "unknown", "unknown", "ERROR Retrieving Browser List!");
    private static final String DEFAULT_MAX_DURATION = "300";
    private static final String DEFAULT_IDLE_TIMEOUT = "90";
    private static final String DEFAULT_SELENIUM_URL = "http://saucelabs.com";
    private static final String DEFAULT_SSH_LOCAL_HOST = "localhost";
    private static final String DEFAULT_SSH_LOCAL_PORT = "8080";
    private static final String DEFAULT_SSH_REMOTE_PORT = "80";
    private static final String DEFAULT_SSH_DOMAIN = "AUTO";
    private static final String DEFAULT_SELENIUM_VERSION = SeleniumVersion.TWO.getVersionNumber();

    /**
     * Entry point into build action.
     *
     * @return
     * @throws IOException
     */
    @NotNull
    //@Override
    public BuildContext call() throws IOException {
        try {
            final SODMappedBuildConfiguration config = new SODMappedBuildConfiguration(buildContext.getBuildDefinition().getCustomConfiguration());
            getSauceAPIFactory().setupProxy(administrationConfigurationManager);
            if (config.isEnabled() && config.isSshEnabled()) {
                startTunnel(config.getTempUsername(), config.getTempApikey(), config.getSshHost(), config.getSshPorts(), config.getSshTunnelPorts(), config.getSshDomains(), config.isAutoDomain());
            }
        }
        catch (Exception e) {
            //catch exceptions so that we don't stop the build from running
            logger.error("Error running Sauce OnDemand BuildConfigurator, attempting to continue", e);
        }
        return buildContext;
    }

    /**
     * Opens the tunnel and adds the tunnel instance to the sauceTunnelManager map.
     *
     * @param username
     * @param apiKey
     * @param localHost
     * @param localPorts
     * @param remotePorts
     * @param sshDomains
     * @param autoDomain
     * @throws IOException
     */
    public void startTunnel(String username, String apiKey, String localHost, String localPorts, String remotePorts, String sshDomains, boolean autoDomain) throws IOException {
        String finalDomain = sshDomains;
        if (autoDomain) {
            finalDomain = "bamboo-" + buildContext.getPlanKey() + ".bamboo";
        }

        int intLocalPort = Integer.parseInt(localPorts);
        int intRemotePort = Integer.parseInt(remotePorts);
        List<String> domainList = Collections.singletonList(finalDomain);
        Object tunnel = getSauceTunnelManager().openConnection(username, apiKey, localHost, intLocalPort, intRemotePort, domainList);
        getSauceTunnelManager().addTunnelToMap(buildContext.getPlanKey(), tunnel);
    }


    /**
     * Populates the <code>context</code> parameter with information to be presented on the 'Edit Configuration' screen.  The
     * list of available Browser types is included in the context.  If an exception occurs during the retrieval of browser information
     * (eg. if a network error occurs retrieving the browser information), then a series of 'unknown' browsers will be added.
     */
    @Override
    protected void populateContextForEdit(final Map<String, Object> context, final BuildConfiguration buildConfiguration, final Plan build) {
        populateCommonContext(context);
        try {
            getSauceAPIFactory().setupProxy(administrationConfigurationManager);
            context.put("browserList", getSauceBrowserFactory().values());
        } catch (IOException e) {
            //TODO are there a set of default browsers that we can use?
            //TODO detect a proxy exception as opposed to all exceptions?
            populateDefaultBrowserList(context);
        } catch (JSONException e) {
            populateDefaultBrowserList(context);
        }
    }

    private void populateDefaultBrowserList(Map<String, Object> context) {
        context.put("browserList", Collections.singletonList(DEFAULT_BROWSER));
    }

    /**
     * Adds a series of default values to the build configuration.  Default values are only supplied if values
     * don't already exist in the configuration.
     */
    @Override
    public void addDefaultValues(@NotNull BuildConfiguration buildConfiguration) {
        super.addDefaultValues(buildConfiguration);

        //only set SSH enabled if we don't have any properties set
        if (!buildConfiguration.getKeys(SODKeys.CUSTOM_PREFIX).hasNext()) {
            addDefaultStringValue(buildConfiguration, SODKeys.SSH_ENABLED_KEY, Boolean.TRUE.toString());
        }
        addDefaultNumberValue(buildConfiguration, SODKeys.MAX_DURATION_KEY, DEFAULT_MAX_DURATION);
        addDefaultNumberValue(buildConfiguration, SODKeys.IDLE_TIMEOUT_KEY, DEFAULT_IDLE_TIMEOUT);
        addDefaultStringValue(buildConfiguration, SODKeys.SELENIUM_VERSION_KEY, DEFAULT_SELENIUM_VERSION);
        addDefaultStringValue(buildConfiguration, SODKeys.RECORD_VIDEO_KEY, Boolean.TRUE.toString());
        addDefaultStringValue(buildConfiguration, SODKeys.SELENIUM_URL_KEY, DEFAULT_SELENIUM_URL);
        addDefaultStringValue(buildConfiguration, SODKeys.SSH_LOCAL_HOST_KEY, DEFAULT_SSH_LOCAL_HOST);
        addDefaultStringValue(buildConfiguration, SODKeys.SSH_LOCAL_PORTS_KEY, DEFAULT_SSH_LOCAL_PORT);
        addDefaultStringValue(buildConfiguration, SODKeys.SSH_REMOTE_PORTS_KEY, DEFAULT_SSH_REMOTE_PORT);
        addDefaultStringValue(buildConfiguration, SODKeys.SSH_AUTO_DOMAIN_KEY, Boolean.TRUE.toString());
        addDefaultStringValue(buildConfiguration, SODKeys.SSH_DOMAINS_KEY, DEFAULT_SSH_DOMAIN);

    }

    private void addDefaultNumberValue(BuildConfiguration buildConfiguration, String configurationKey, String defaultValue) {
        if (!NumberUtils.isNumber(buildConfiguration.getString(configurationKey))) {
            buildConfiguration.setProperty(configurationKey, defaultValue);
        }
    }

    private void addDefaultStringValue(BuildConfiguration buildConfiguration, String configurationKey, String defaultValue) {
        if (StringUtils.isBlank(buildConfiguration.getString(configurationKey))) {
            buildConfiguration.setProperty(configurationKey, defaultValue);
        }
    }

    private void populateCommonContext(final Map<String, Object> context) {
        context.put("hasValidSauceConfig", hasValidSauceConfig());
    }

    /**
     * @return boolean indicating whether the Sauce configuration specified in the administration interface
     */
    public boolean hasValidSauceConfig() {
        AdministrationConfiguration adminConfig = administrationConfigurationManager.getAdministrationConfiguration();
        return (StringUtils.isNotBlank(adminConfig.getSystemProperty(SODKeys.SOD_USERNAME_KEY))
                && StringUtils.isNotBlank(adminConfig.getSystemProperty(SODKeys.SOD_ACCESSKEY_KEY))
                && StringUtils.isNotBlank(adminConfig.getSystemProperty(SODKeys.SELENIUM_HOST_KEY))
                && StringUtils.isNotBlank(adminConfig.getSystemProperty(SODKeys.SELENIUM_PORT_KEY)));
    }

    public AdministrationConfigurationManager getAdministrationConfigurationManager() {
        return administrationConfigurationManager;
    }

    public void setAdministrationConfigurationManager(AdministrationConfigurationManager administrationConfigurationManager) {
        this.administrationConfigurationManager = administrationConfigurationManager;
    }

    public void setSauceTunnelManager(SauceTunnelManager sauceTunnelManager) {
        this.sauceTunnelManager = sauceTunnelManager;
    }

    public void setSauceBrowserFactory(BrowserFactory sauceBrowserFactory) {
        this.sauceBrowserFactory = sauceBrowserFactory;
    }

    public void setSauceAPIFactory(SauceFactory sauceAPIFactory) {
        this.sauceAPIFactory = sauceAPIFactory;
    }
    
    public SauceTunnelManager getSauceTunnelManager() {
        if (sauceTunnelManager == null) {
            setSauceTunnelManager(SauceConnectTwoManager.getInstance());
        }
        return sauceTunnelManager;
    }

    public SauceFactory getSauceAPIFactory() {
        if (sauceAPIFactory == null) {
            setSauceAPIFactory(SauceFactory.getInstance());
        }
        return sauceAPIFactory;
    }

    public BrowserFactory getSauceBrowserFactory() {
        if (sauceBrowserFactory == null) {
            setSauceBrowserFactory(BrowserFactory.getInstance());
        }
        return sauceBrowserFactory;
    }


}
