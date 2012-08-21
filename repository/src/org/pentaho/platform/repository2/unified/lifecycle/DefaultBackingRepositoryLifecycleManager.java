/*
 * This program is free software; you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License, version 2 as published by the Free Software 
 * Foundation.
 *
 * You should have received a copy of the GNU General Public License along with this 
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/gpl-2.0.html 
 * or from the Free Software Foundation, Inc., 
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */
package org.pentaho.platform.repository2.unified.lifecycle;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FilenameUtils;
import org.pentaho.platform.api.engine.IPentahoObjectFactory;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.engine.ObjectFactoryException;
import org.pentaho.platform.api.engine.security.userroledao.IPentahoRole;
import org.pentaho.platform.api.engine.security.userroledao.IPentahoUser;
import org.pentaho.platform.api.engine.security.userroledao.IUserRoleDao;
import org.pentaho.platform.api.mt.ITenant;
import org.pentaho.platform.api.mt.ITenantManager;
import org.pentaho.platform.api.mt.ITenantedPrincipleNameResolver;
import org.pentaho.platform.api.repository2.unified.IBackingRepositoryLifecycleManager;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.api.repository2.unified.RepositoryFileAcl;
import org.pentaho.platform.api.repository2.unified.RepositoryFilePermission;
import org.pentaho.platform.api.repository2.unified.RepositoryFileSid;
import org.pentaho.platform.api.repository2.unified.RepositoryFileAcl.Builder;
import org.pentaho.platform.api.repository2.unified.RepositoryFileSid.Type;
import org.pentaho.platform.core.mt.Tenant;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.engine.core.system.StandaloneSession;
import org.pentaho.platform.engine.core.system.TenantUtils;
import org.pentaho.platform.repository2.unified.IRepositoryFileAclDao;
import org.pentaho.platform.repository2.unified.IRepositoryFileDao;
import org.pentaho.platform.repository2.unified.ServerRepositoryPaths;
import org.springframework.security.Authentication;
import org.springframework.security.GrantedAuthority;
import org.springframework.security.GrantedAuthorityImpl;
import org.springframework.security.context.SecurityContextHolder;
import org.springframework.security.providers.UsernamePasswordAuthenticationToken;
import org.springframework.security.userdetails.User;
import org.springframework.security.userdetails.UserDetails;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/**
 * Default {@link IBackingRepositoryLifecycleManager} implementation.
 * <p/>
 * <p>
 * <strong>
 * Note: You must be careful when changing, overriding, or substituting this class. The configuration of
 * {@code DefaultPentahoJackrabbitAccessControlHelper} depends on the behavior of this class.
 * </strong>
 * </p>
 *
 * @author mlowery
 */
public class DefaultBackingRepositoryLifecycleManager implements  IBackingRepositoryLifecycleManager {

  
  // ~ Static fields/initializers ======================================================================================

  // ~ Instance fields =================================================================================================
  IUserRoleDao userRoleDao;
  ITenantManager tenantManager;
  protected String repositoryAdminUsername;
  
  protected String tenantAdminRoleName;
  protected String systemTenantAdminUserName;
  protected String singleTenantAdminUserName;

  protected String tenantAuthenticatedRoleName;

  protected TransactionTemplate txnTemplate;

  protected IRepositoryFileDao repositoryFileDao;

  protected IRepositoryFileAclDao repositoryFileAclDao;
  
  ITenantedPrincipleNameResolver tenantedUserNameUtils;  
  ITenantedPrincipleNameResolver tenantedRoleNameUtils;


  // ~ Constructors ====================================================================================================

  public DefaultBackingRepositoryLifecycleManager(final IRepositoryFileDao contentDao,
                                                  final IRepositoryFileAclDao repositoryFileAclDao, 
                                                  final TransactionTemplate txnTemplate,
                                                  final String repositoryAdminUsername, 
                                                  final String systemTenantAdminUserName,
                                                  final String singleTenantAdminUserName,
                                                  final String tenantAdminRoleName,
                                                  final String tenantAuthenticatedRoleName, 
                                                  final ITenantedPrincipleNameResolver tenantedUserNameUtils, 
                                                  final ITenantedPrincipleNameResolver tenantedRoleNameUtils) {
    Assert.notNull(contentDao);
    Assert.notNull(repositoryFileAclDao);
    Assert.notNull(txnTemplate);
    Assert.hasText(repositoryAdminUsername);
    Assert.hasText(tenantAuthenticatedRoleName);
    this.repositoryFileDao = contentDao;
    this.repositoryFileAclDao = repositoryFileAclDao;
    this.txnTemplate = txnTemplate;
    this.repositoryAdminUsername = repositoryAdminUsername;
    this.tenantAuthenticatedRoleName = tenantAuthenticatedRoleName;
    this.tenantedUserNameUtils = tenantedUserNameUtils;
    this.tenantedRoleNameUtils = tenantedRoleNameUtils;
    this.tenantAdminRoleName = tenantAdminRoleName;
    this.systemTenantAdminUserName = systemTenantAdminUserName;
    this.singleTenantAdminUserName = singleTenantAdminUserName;
    initTransactionTemplate();

    
  }

  // ~ Methods =========================================================================================================

  protected void initTransactionTemplate() {
    // a new transaction must be created (in order to run with the correct user privileges)
    txnTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public synchronized void newTenant(final String tenantId) {
  }

  @Override
  public synchronized void newUser(final String tenantId, final String username) {
    userRoleDao.createUserHomeFolder(new Tenant(tenantId, true), username);
  }


  @Override
  public void newTenant() {
  }

  @Override
  public void newUser() {
  }

  @Override
  public synchronized void shutdown() {
  }

  @Override
  public synchronized void startup() {
    ITenant defaultTenant = null;
    loginAsRepositoryAdmin();
    ITenantManager tenantMgr = getTenantManager();
    ITenant systemTenant = tenantMgr.createTenant(null, ServerRepositoryPaths.getPentahoRootFolderName(), tenantAdminRoleName, tenantAuthenticatedRoleName, "Anonymous");
    if (systemTenant != null) {
      userRoleDao.createUser(systemTenant, systemTenantAdminUserName, "password", "", new String[]{tenantAdminRoleName, tenantAuthenticatedRoleName});
      IPentahoSession origSession = PentahoSessionHolder.getSession();
      Authentication origAuth = SecurityContextHolder.getContext().getAuthentication();
      login(systemTenantAdminUserName, systemTenant, new String[]{tenantAdminRoleName, tenantAuthenticatedRoleName});
      try {
        defaultTenant = tenantMgr.getTenant(systemTenant.getRootFolderAbsolutePath() + RepositoryFile.SEPARATOR + TenantUtils.TENANTID_SINGLE_TENANT);
        if(defaultTenant == null) {
          // We'll create the default tenant here... maybe this isn't the best place.
          defaultTenant = tenantMgr.createTenant(systemTenant, TenantUtils.TENANTID_SINGLE_TENANT, tenantAdminRoleName, tenantAuthenticatedRoleName, "Anonymous");
          userRoleDao.createUser(defaultTenant, singleTenantAdminUserName, "password", "", new String[]{tenantAdminRoleName});
        }
      } finally {
        PentahoSessionHolder.setSession(origSession);
        SecurityContextHolder.getContext().setAuthentication(origAuth);
      }
    }    
  }

  /**
   * @return the {@link IBackingRepositoryLifecycleManager} that this instance will use. If none has been specified,
   * it will default to getting the information from {@link PentahoSystem.get()}
   */
  public ITenantManager getTenantManager() {
    // Check ... if we haven't been injected with a lifecycle manager, get one from PentahoSystem
    try {
      IPentahoObjectFactory objectFactory = PentahoSystem.getObjectFactory();
      IPentahoSession pentahoSession = PentahoSessionHolder.getSession();
      return (null != tenantManager ? tenantManager : objectFactory.get(ITenantManager.class, "tenantMgrProxy", pentahoSession));
    } catch (ObjectFactoryException e) {
      return null;
    }
  }

  /**
   * Sets the {@link IBackingRepositoryLifecycleManager} to be used by this instance
   * @param lifecycleManager the lifecycle manager to use (can not be null)
   */
  public void setTenantManager(final ITenantManager tenantManager) {
    assert(null != tenantManager);
    this.tenantManager = tenantManager;
  }
  
  public IUserRoleDao getUserRoleDao() {
    return userRoleDao;
  }
  
  public void setUserRoleDao(IUserRoleDao userRoleDao) {
    this.userRoleDao = userRoleDao;
  }

  /**
   * Logs in with given username.
   *
   * @param username username of user
   * @param tenantId tenant to which this user belongs
   * @tenantAdmin true to add the tenant admin authority to the user's roles
   */
  private void login(final String username, final ITenant tenant, final String[] roles) {
    StandaloneSession pentahoSession = new StandaloneSession(tenantedUserNameUtils.getPrincipleId(tenant, username));
    pentahoSession.setAuthenticated(tenant.getId(), tenantedUserNameUtils.getPrincipleId(tenant, username));
    PentahoSessionHolder.setSession(pentahoSession);
    pentahoSession.setAttribute(IPentahoSession.TENANT_ID_KEY, tenant.getId());
    final String password = "password";

    List<GrantedAuthority> authList = new ArrayList<GrantedAuthority>();

    for (String role : roles) {
      authList.add(new GrantedAuthorityImpl(tenantedRoleNameUtils.getPrincipleId(tenant, role)));
    }
    GrantedAuthority[] authorities = authList.toArray(new GrantedAuthority[0]);
    UserDetails userDetails = new User(username, password, true, true, true, true, authorities);
    Authentication auth = new UsernamePasswordAuthenticationToken(userDetails, password, authorities);
    PentahoSessionHolder.setSession(pentahoSession);
    // this line necessary for Spring Security's MethodSecurityInterceptor
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  protected void loginAsRepositoryAdmin() {
    StandaloneSession pentahoSession = new StandaloneSession(repositoryAdminUsername);
    pentahoSession.setAuthenticated(repositoryAdminUsername);
    final GrantedAuthority[] repositoryAdminAuthorities = new GrantedAuthority[]{};
    final String password = "ignored";
    UserDetails repositoryAdminUserDetails = new User(repositoryAdminUsername, password, true, true, true, true,
        repositoryAdminAuthorities);
    Authentication repositoryAdminAuthentication = new UsernamePasswordAuthenticationToken(repositoryAdminUserDetails,
        password, repositoryAdminAuthorities);
    PentahoSessionHolder.setSession(pentahoSession);
    // this line necessary for Spring Security's MethodSecurityInterceptor
    SecurityContextHolder.getContext().setAuthentication(repositoryAdminAuthentication);
  }
}
