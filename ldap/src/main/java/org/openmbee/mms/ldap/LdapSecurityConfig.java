package org.openmbee.mms.ldap;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.openmbee.mms.core.config.AuthorizationConstants;
import org.openmbee.mms.data.domains.global.Group;
import org.openmbee.mms.rdb.repositories.GroupRepository;
import org.openmbee.mms.rdb.repositories.UserRepository;
import org.openmbee.mms.data.domains.global.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.filter.*;
import org.springframework.ldap.support.LdapEncoder;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.authentication.configurers.ldap.LdapAuthenticationProviderConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.ldap.SpringSecurityLdapTemplate;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.ldap.userdetails.LdapAuthoritiesPopulator;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.naming.Context;

@Configuration
@Conditional(LdapCondition.class)
@EnableTransactionManagement
public class LdapSecurityConfig {

    private static Logger logger = LoggerFactory.getLogger(LdapSecurityConfig.class);

    @Value("${ldap.ad.enabled:false}")
    private Boolean adEnabled;

    @Value("${ldap.ad.domain:#{null}}")
    private String adDomain;

    @Value("${ldap.provider.url:#{null}}")
    private String providerUrl;

    @Value("${ldap.provider.userdn:#{null}}")
    private String providerUserDn;

    @Value("${ldap.provider.password:#{null}}")
    private String providerPassword;

    @Value("${ldap.provider.base:#{null}}")
    private String providerBase;

    @Value("#{'${ldap.user.dn.pattern:uid={0}}'.split(';')}")
    private List<String> userDnPattern;

    @Value("${ldap.user.attributes.username:uid}")
    private String userAttributesUsername;

    @Value("${ldap.user.attributes.firstname:givenname}")
    private String userAttributesFirstName;

    @Value("${ldap.user.attributes.lastname:sn}")
    private String userAttributesLastName;

    @Value("${ldap.user.attributes.email:mail}")
    private String userAttributesEmail;

    @Value("${ldap.user.attributes.update:24}")
    private int userAttributesUpdate;

    @Value("${ldap.group.search.base:#{''}}")
    private String groupSearchBase;

    @Value("${ldap.group.role.attribute:cn}")
    private String groupRoleAttribute;

    @Value("${ldap.group.search.filter:(uniqueMember={0})}")
    private String groupSearchFilter;

    @Value("${ldap.user.search.base:#{''}}")
    private String userSearchBase;

    @Value("${ldap.user.search.filter:(uid={0})}")
    private String userSearchFilter;

    private UserRepository userRepository;
    private GroupRepository groupRepository;

    @Autowired
    public void setUserRepository(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Autowired
    public void setGroupRepository(GroupRepository groupRepository) {
        this.groupRepository = groupRepository;
    }

    @Autowired
    public void configureLdapAuth(AuthenticationManagerBuilder auth,
        LdapAuthoritiesPopulator ldapAuthoritiesPopulator, @Qualifier("contextSource") BaseLdapPathContextSource contextSource)
        throws Exception {
        if (providerUrl != null) {
            logger.info("LDAP Module is loading...");
        /*
            see this article : https://spring.io/guides/gs/authenticating-ldap/
            We  redefine our own LdapAuthoritiesPopulator which need ContextSource().
            We need to delegate the creation of the contextSource out of the builder-configuration.
        */
            if (adEnabled) {
                auth.authenticationProvider(activeDirectoryLdapAuthenticationProvider());
            } else {
                String[] userPatterns = userDnPattern.toArray(new String[0]);
                LdapAuthenticationProviderConfigurer<AuthenticationManagerBuilder> authProviderConfigurer = auth.ldapAuthentication();
                authProviderConfigurer.userDnPatterns(userPatterns);
                authProviderConfigurer.userSearchBase(userSearchBase);
                authProviderConfigurer.userSearchFilter(userSearchFilter);
                authProviderConfigurer.groupSearchBase(groupSearchBase);
                authProviderConfigurer.groupRoleAttribute(groupRoleAttribute);
                authProviderConfigurer.groupSearchFilter(groupSearchFilter);
                authProviderConfigurer.rolePrefix("");
                authProviderConfigurer.ldapAuthoritiesPopulator(ldapAuthoritiesPopulator);
                authProviderConfigurer.contextSource(contextSource);
            }
        }
    }

    @Bean
    LdapAuthoritiesPopulator ldapAuthoritiesPopulator(@Qualifier("contextSource") BaseLdapPathContextSource baseContextSource) {

        /*
          Specificity here : we don't get the Role by reading the members of available groups (which is implemented by
          default in Spring security LDAP), but we retrieve the groups the user belongs to.
         */
        class CustomLdapAuthoritiesPopulator implements LdapAuthoritiesPopulator {

            final SpringSecurityLdapTemplate ldapTemplate;

            private CustomLdapAuthoritiesPopulator(BaseLdapPathContextSource ldapContextSource) {
                ldapTemplate = new SpringSecurityLdapTemplate(ldapContextSource);
            }

            @Override
            public Collection<? extends GrantedAuthority> getGrantedAuthorities(
                DirContextOperations userData, String username) {
                logger.debug("Populating authorities using LDAP");
                Optional<User> userOptional = userRepository.findByUsername(username);

                if (userOptional.isEmpty()) {
                    logger.info("No user record for {} in the userRepository, creating...", userData.getDn());
                    User newUser = createLdapUser(userData);
                    userOptional = Optional.of(newUser);
                }

                User user = userOptional.get();
                if (user.getModified().isBefore(Instant.now().minus(userAttributesUpdate, ChronoUnit.HOURS))) {
                    saveLdapUser(userData, user);
                }
                user.setPassword(null);

                StringBuilder userDnBuilder = new StringBuilder();
                userDnBuilder.append(userData.getDn().toString());
                if (providerBase != null && !providerBase.isEmpty()) {
                    userDnBuilder.append(',');
                    userDnBuilder.append(providerBase);
                }
                String userDn = userDnBuilder.toString();

                List<Group> definedGroups = groupRepository.findAll();
                OrFilter orFilter = new OrFilter();

                for (Group definedGroup : definedGroups) {
                    orFilter.or(new EqualsFilter(groupRoleAttribute, definedGroup.getName()));
                }

                AndFilter andFilter = new AndFilter();
                HardcodedFilter groupsFilter = new HardcodedFilter(
                    groupSearchFilter.replace("{0}", LdapEncoder.filterEncode(userDn)));
                andFilter.and(groupsFilter);
                andFilter.and(orFilter);

                String filter = andFilter.encode();
                logger.debug("Searching LDAP with filter: {}", filter);

                Set<String> memberGroups = ldapTemplate
                    .searchForSingleAttributeValues(groupSearchBase, filter, new Object[]{""}, groupRoleAttribute);
                logger.debug("LDAP search result: {}", Arrays.toString(memberGroups.toArray()));

                Set<Group> addGroups = new HashSet<>();
                for (String memberGroup : memberGroups) {
                    Optional<Group> group = groupRepository.findByName(memberGroup);
                    group.ifPresent(addGroups::add);
                }

                if (logger.isDebugEnabled()) {
                    if ((long) addGroups.size() > 0) {
                        addGroups.forEach(group -> {
                            logger.debug("Group received: {}", group.getName());
                        });
                    } else {
                        logger.debug("No configured groups returned from LDAP");
                    }
                }

                user.setGroups(addGroups);
                userRepository.save(user);

                List<GrantedAuthority> auths = AuthorityUtils
                    .createAuthorityList(memberGroups.toArray(new String[0]));
                if (user.isAdmin()) {
                    auths.add(new SimpleGrantedAuthority(AuthorizationConstants.MMSADMIN));
                }
                auths.add(new SimpleGrantedAuthority(AuthorizationConstants.EVERYONE));
                return auths;
            }
        }

        return new CustomLdapAuthoritiesPopulator(baseContextSource);

    }

    @Bean
    public AuthenticationProvider activeDirectoryLdapAuthenticationProvider() {
        ActiveDirectoryLdapAuthenticationProvider provider = new ActiveDirectoryLdapAuthenticationProvider(adDomain, providerUrl, providerBase);

        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, providerUrl);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, providerUserDn);
        env.put(Context.SECURITY_CREDENTIALS, providerPassword);

        provider.setContextEnvironmentProperties(env);

        provider.setSearchFilter(userSearchFilter);
        provider.setConvertSubErrorCodesToExceptions(true);
        provider.setUseAuthenticationRequestCredentials(true);
        return provider;
    }

    @Bean
    public LdapContextSource contextSource() {
        LdapContextSource contextSource = new LdapContextSource();

        logger.debug("Initializing LDAP ContextSource with the following values: ");

        contextSource.setUrl(providerUrl);
        contextSource.setBase(providerBase);
        contextSource.setUserDn(providerUserDn);
        contextSource.setPassword(providerPassword);

        logger.debug("BaseLdapPath: " + contextSource.getBaseLdapPathAsString());
        logger.debug("UserDn: " + contextSource.getUserDn());

        return contextSource;
    }

    private User saveLdapUser(DirContextOperations userData, User saveUser) {
        if (saveUser.getEmail() == null ||
            !saveUser.getEmail().equals(userData.getStringAttribute(userAttributesEmail))
        ) {
            saveUser.setEmail(userData.getStringAttribute(userAttributesEmail));
        }
        if (saveUser.getFirstName() == null ||
            !saveUser.getFirstName().equals(userData.getStringAttribute(userAttributesFirstName))
        ) {
            saveUser.setFirstName(userData.getStringAttribute(userAttributesFirstName));
        }
        if (saveUser.getLastName() == null ||
            !saveUser.getLastName().equals(userData.getStringAttribute(userAttributesLastName))
        ) {
            saveUser.setLastName(userData.getStringAttribute(userAttributesLastName));
        }

        return saveUser;
    }

    private User createLdapUser(DirContextOperations userData) {
        String username = userData.getStringAttribute(userAttributesUsername);
        logger.debug("Creating user for {} using LDAP", username);
        User user = saveLdapUser(userData, new User());
        user.setUsername(username);
        user.setEnabled(true);
        user.setAdmin(false);
        userRepository.save(user);

        return user;
    }
}