package com.intellij.util.net.ssl;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.StreamUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The central piece of our SSL support - special kind of trust manager, that asks user to confirm
 * untrusted certificate, e.g. if it wasn't found in system-wide storage.
 *
 * @author Mikhail Golubev
 */
public class ConfirmingTrustManager extends ClientOnlyTrustManager {
  private static final Logger LOG = Logger.getInstance(ConfirmingTrustManager.class);
  private static final X509Certificate[] NO_CERTIFICATES = new X509Certificate[0];
  private static final X509TrustManager MISSING_TRUST_MANAGER = new ClientOnlyTrustManager() {
    @Override
    public void checkServerTrusted(X509Certificate[] certificates, String s) throws CertificateException {
      LOG.debug("Trust manager is missing. Retreating.");
      throw new CertificateException("Missing trust manager");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return NO_CERTIFICATES;
    }
  };

  public static ConfirmingTrustManager createForStorage(@NotNull String path, @NotNull String password) {
    return new ConfirmingTrustManager(getSystemDefault(), new MutableTrustManager(path, password));
  }

  private static X509TrustManager getSystemDefault() {
    try {
      TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      // hacky way to get default trust store
      factory.init((KeyStore)null);
      // assume that only X509 TrustManagers exist
      X509TrustManager systemManager = findX509TrustManager(factory.getTrustManagers());
      if (systemManager != null && systemManager.getAcceptedIssuers().length != 0) {
        return systemManager;
      }
    }
    catch (Exception e) {
      LOG.error("Cannot get system trust store", e);
    }
    return MISSING_TRUST_MANAGER;
  }

  private final X509TrustManager mySystemManager;
  private final MutableTrustManager myCustomManager;


  private ConfirmingTrustManager(X509TrustManager system, MutableTrustManager custom) {
    mySystemManager = system;
    myCustomManager = custom;
  }

  private static X509TrustManager findX509TrustManager(TrustManager[] managers) {
    for (TrustManager manager : managers) {
      if (manager instanceof X509TrustManager) {
        return (X509TrustManager)manager;
      }
    }
    return null;
  }

  @Override
  public void checkServerTrusted(final X509Certificate[] certificates, String s) throws CertificateException {
    try {
      mySystemManager.checkServerTrusted(certificates, s);
    }
    catch (CertificateException e) {
      // check-then-act sequence
      synchronized (myCustomManager) {
        try {
          myCustomManager.checkServerTrusted(certificates, s);
        }
        catch (CertificateException e2) {
          if (myCustomManager.isBroken() || !confirmAndUpdate(certificates)) {
            throw e;
          }
        }
      }
    }
  }

  private boolean confirmAndUpdate(final X509Certificate[] chain) {
    Application app = ApplicationManager.getApplication();
    final X509Certificate endPoint = chain[0];
    if (app.isUnitTestMode() || app.isHeadlessEnvironment()) {
      myCustomManager.addCertificate(endPoint);
      return true;
    }
    boolean accepted = CertificatesManager.showAcceptDialog(new Callable<DialogWrapper>() {
      @Override
      public DialogWrapper call() throws Exception {
        // TODO may be another kind of warning, if default trust store is missing
        return CertificateWarningDialog.createUntrustedCertificateWarning(endPoint);
      }
    });
    if (accepted) {
      LOG.info("Certificate was accepted");
      myCustomManager.addCertificate(endPoint);
    }
    return accepted;
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return ArrayUtil.mergeArrays(mySystemManager.getAcceptedIssuers(), myCustomManager.getAcceptedIssuers());
  }

  public X509TrustManager getSystemManager() {
    return mySystemManager;
  }

  public MutableTrustManager getCustomManager() {
    return myCustomManager;
  }

  /**
   * Trust manager that supports addition of new certificates (most likely self-signed) to underlying physical
   * key store.
   */
  static class MutableTrustManager extends ClientOnlyTrustManager {
    private final String myPath;
    private final String myPassword;
    private final TrustManagerFactory myFactory;
    private final KeyStore myKeyStore;
    private final ReadWriteLock myLock = new ReentrantReadWriteLock();
    private final Lock myReadLock = myLock.readLock();
    private final Lock myWriteLock = myLock.writeLock();
    // reloaded after each modification
    private X509TrustManager myTrustManager;

    private MutableTrustManager(@NotNull String path, @NotNull String password) {
      myPath = path;
      myPassword = password;
      // initialization step
      myWriteLock.lock();
      try {
        myFactory = createFactory();
        myKeyStore = createKeyStore(path, password);
        myTrustManager = initFactoryAndGetManager();
      }
      finally {
        myWriteLock.unlock();
      }
    }

    private static TrustManagerFactory createFactory() {
      try {
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      }
      catch (NoSuchAlgorithmException e) {
        return null;
      }
    }

    private static KeyStore createKeyStore(@NotNull String path, @NotNull String password) {
      KeyStore keyStore;
      try {
        keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        File cacertsFile = new File(path);
        if (cacertsFile.exists()) {
          FileInputStream stream = null;
          try {
            stream = new FileInputStream(path);
            keyStore.load(stream, password.toCharArray());
          }
          finally {
            StreamUtil.closeStream(stream);
          }
        }
        else {
          if (!FileUtil.createParentDirs(cacertsFile)) {
            LOG.error("Cannot create directories: " + cacertsFile.getParent());
            return null;
          }
          keyStore.load(null, password.toCharArray());
        }
      }
      catch (Exception e) {
        LOG.error(e);
        return null;
      }
      return keyStore;
    }


    /**
     * Add certificate to underlying trust store.
     *
     * @param certificate server's certificate
     * @return whether the operation was successful
     */
    public boolean addCertificate(@NotNull X509Certificate certificate) {
      myWriteLock.lock();
      try {
        if (isBroken()) {
          return false;
        }
        myKeyStore.setCertificateEntry(createAlias(certificate), certificate);
        flushKeyStore();
        // trust manager should be updated each time its key store was modified
        myTrustManager = initFactoryAndGetManager();
        return true;
      }
      catch (Exception e) {
        LOG.error("Can't add certificate", e);
        return false;
      }
      finally {
        myWriteLock.unlock();
      }
    }

    /**
     * Add certificate, loaded from file at {@code path}, to underlying trust store.
     *
     * @param path path to file containing certificate
     * @return whether the operation was successful
     */
    public boolean addCertificate(@NotNull String path) {
      X509Certificate certificate = CertificateUtil.loadX509Certificate(path);
      return certificate != null && addCertificate(certificate);
    }

    private static String createAlias(@NotNull X509Certificate certificate) {
      return new CertificateWrapper(certificate).getSubjectField(CertificateWrapper.CommonField.COMMON_NAME);
    }

    /**
     * Remove certificate from underlying trust store.
     *
     * @param certificate certificate alias
     * @return whether the operation was successful
     */
    public boolean removeCertificate(@NotNull X509Certificate certificate) {
      return removeCertificate(createAlias(certificate));
    }

    /**
     * Remove certificate, specified by its alias, from underlying trust store.
     *
     * @param alias certificate's alias
     * @return true if removal operation was successful and false otherwise
     */
    public boolean removeCertificate(@NotNull String alias) {
      myWriteLock.lock();
      try {
        if (isBroken()) {
          return false;
        }
        myKeyStore.deleteEntry(alias);
        flushKeyStore();
        // trust manager should be updated each time its key store was modified
        myTrustManager = initFactoryAndGetManager();
        return true;
      }
      catch (Exception e) {
        LOG.error("Can't remove certificate for alias: " + alias, e);
        return false;
      }
      finally {
        myWriteLock.unlock();
      }
    }

    /**
     * Get certificate, specified by its alias, from underlying trust store.
     *
     * @param alias certificate's alias
     * @return certificate or null if it's not present
     */
    @Nullable
    public X509Certificate getCertificate(@NotNull String alias) {
      myReadLock.lock();
      try {
        return (X509Certificate)myKeyStore.getCertificate(alias);
      }
      catch (KeyStoreException e) {
        return null;
      }
      finally {
        myReadLock.unlock();
      }
    }

    /**
     * Select all available certificates from underlying trust store. Result list is not supposed to be modified.
     *
     * @return certificates
     */
    public List<X509Certificate> getCertificates() {
      myReadLock.lock();
      List<X509Certificate> certificates = new ArrayList<X509Certificate>();
      try {
        Iterator<String> iterator = ContainerUtil.iterate(myKeyStore.aliases());
        while (iterator.hasNext()) {
          certificates.add(getCertificate(iterator.next()));
        }
        return ContainerUtil.immutableList(certificates);
      }
      catch (Exception e) {
        LOG.error(e);
        return ContainerUtil.emptyList();
      }
      finally {
        myReadLock.unlock();
      }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] certificates, String s) throws CertificateException {
      myReadLock.lock();
      try {
        if (keyStoreIsEmpty() || isBroken()) {
          throw new CertificateException();
        }
        myTrustManager.checkServerTrusted(certificates, s);
      }
      finally {
        myReadLock.unlock();
      }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      myReadLock.lock();
      try {
        // trust no one if broken
        if (keyStoreIsEmpty() || isBroken()) {
          return NO_CERTIFICATES;
        }
        return myTrustManager.getAcceptedIssuers();
      }
      finally {
        myReadLock.unlock();
      }
    }

    // Guarded by caller's lock
    private boolean keyStoreIsEmpty() {
      try {
        return myKeyStore.size() == 0;
      }
      catch (KeyStoreException e) {
        LOG.error(e);
        return true;
      }
    }

    // Guarded by caller's lock
    private X509TrustManager initFactoryAndGetManager() {
      try {
        if (myFactory != null && myKeyStore != null) {
          myFactory.init(myKeyStore);
          return findX509TrustManager(myFactory.getTrustManagers());
        }
      }
      catch (KeyStoreException e) {
        LOG.error(e);
      }
      return null;
    }

    // Guarded by caller's lock
    private boolean isBroken() {
      return myKeyStore == null || myFactory == null || myTrustManager == null;
    }

    private void flushKeyStore() throws Exception {
      FileOutputStream stream = new FileOutputStream(myPath);
      try {
        myKeyStore.store(stream, myPassword.toCharArray());
      }
      finally {
        StreamUtil.closeStream(stream);
      }
    }
  }
}
