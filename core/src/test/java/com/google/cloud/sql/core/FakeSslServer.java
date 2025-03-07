/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.sql.core;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.KeyStore.PasswordProtection;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

public class FakeSslServer {

  int start(final String ip) throws InterruptedException {
    final CountDownLatch countDownLatch = new CountDownLatch(1);
    final AtomicInteger pickedPort = new AtomicInteger();

    new Thread(
            () -> {
              try {
                CertificateFactory certFactory = CertificateFactory.getInstance("X.509");

                KeyStore authKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                authKeyStore.load(null, null);
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                PKCS8EncodedKeySpec keySpec =
                    new PKCS8EncodedKeySpec(
                        CloudSqlCoreTestingBase.decodeBase64StripWhitespace(
                            TestKeys.SERVER_CERT_PRIVATE_KEY));
                PrivateKey privateKey = keyFactory.generatePrivate(keySpec);
                PrivateKeyEntry serverCert =
                    new PrivateKeyEntry(
                        privateKey,
                        new Certificate[] {
                          certFactory.generateCertificate(
                              new ByteArrayInputStream(TestKeys.SERVER_CERT.getBytes(UTF_8)))
                        });
                authKeyStore.setEntry(
                    "serverCert", serverCert, new PasswordProtection(new char[0]));
                KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(authKeyStore, new char[0]);

                final X509Certificate signingCaCert =
                    (X509Certificate)
                        certFactory.generateCertificate(
                            new ByteArrayInputStream(TestKeys.SIGNING_CA_CERT.getBytes(UTF_8)));

                KeyStore trustKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                trustKeyStore.load(null, null);
                trustKeyStore.setCertificateEntry("instance", signingCaCert);
                TrustManagerFactory tmf = TrustManagerFactory.getInstance("X.509");
                tmf.init(trustKeyStore);

                SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
                sslContext.init(
                    keyManagerFactory.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
                SSLServerSocketFactory sslServerSocketFactory = sslContext.getServerSocketFactory();
                SSLServerSocket sslServerSocket =
                    (SSLServerSocket)
                        sslServerSocketFactory.createServerSocket(0, 5, InetAddress.getByName(ip));
                sslServerSocket.setNeedClientAuth(true);

                pickedPort.set(sslServerSocket.getLocalPort());
                countDownLatch.countDown();

                for (; ; ) {
                  SSLSocket socket = (SSLSocket) sslServerSocket.accept();
                  try {
                    socket.startHandshake();
                    socket
                        .getOutputStream()
                        .write(CloudSqlCoreTestingBase.SERVER_MESSAGE.getBytes(UTF_8));
                    socket.close();
                  } catch (Exception e) {
                    e.printStackTrace();
                  }
                }
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .start();

    countDownLatch.await();

    return pickedPort.get();
  }
}
