package uk.gegc.quizmaker.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for feature flags
 */
@Component
@ConfigurationProperties(prefix = "quizmaker.features")
public class FeatureFlags {
    
    private boolean shareLinks = true;
    private boolean organizations = true;
    private boolean billing = false;
    
    public boolean isShareLinks() {
        return shareLinks;
    }
    
    public void setShareLinks(boolean shareLinks) {
        this.shareLinks = shareLinks;
    }
    
    public boolean isOrganizations() {
        return organizations;
    }
    
    public void setOrganizations(boolean organizations) {
        this.organizations = organizations;
    }
    
    public boolean isBilling() {
        return billing;
    }
    
    public void setBilling(boolean billing) {
        this.billing = billing;
    }
}
