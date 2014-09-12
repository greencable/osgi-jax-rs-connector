/*******************************************************************************
 * Copyright (c) 2012 EclipseSource and others. All rights reserved. This program and the
 * accompanying materials are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html Contributors: Holger Staudacher - initial API and
 * implementation Dragos Dascalita - disbaled autodiscovery
 ******************************************************************************/
package com.eclipsesource.jaxrs.publisher.internal;

import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javax.print.attribute.standard.DateTimeAtCompleted;
import javax.servlet.ServletException;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Request;

import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.servlet.ServletContainer;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;

public class JerseyContext {

  private final RootApplication application;
  private ServletContainer servletContainer;
  private final HttpService httpService;
  private final String rootPath;
  private boolean isApplicationRegistered;
  private Timer timerResource = null;
  private long lastModification;
  private final long maxDelay = 1000;

  public JerseyContext( HttpService httpService, String rootPath ) {
    this.httpService = httpService;
    this.rootPath = rootPath == null
                                    ? "/services"
                                    : rootPath;
    this.application = new RootApplication();
    disableAutoDiscovery();
    this.servletContainer = new ServletContainer( ResourceConfig.forApplication( application ) );
  }

  private void disableAutoDiscovery() {
    // don't look for implementations described by META-INF/services/*
    this.application.addProperty( ServerProperties.METAINF_SERVICES_LOOKUP_DISABLE, false );
    // disable auto discovery on server, as it's handled via OSGI
    this.application.addProperty( ServerProperties.FEATURE_AUTO_DISCOVERY_DISABLE, true );
  }

  public void addResource( Object resource ) {
    getRootApplication().addResource( resource );
    registerServletWhenNotAlreadyRegistered();
    updateServletRegistration();
  }

  void updateServletRegistration() {
    if( timerResource == null ) {
      timerResource = new Timer();
      try {
        TimerTask resourceTask = new TimerTask() {

          @Override
          public void run() {
            if( ( System.currentTimeMillis() - lastModification ) > maxDelay ) {
              if( isApplicationRegistered ) {
                Thread.currentThread().setContextClassLoader( Request.class.getClassLoader() );
                getServletContainer().reload( ResourceConfig.forApplication( application ) );
                unregisterServletWhenNoresourcePresents();
              }
              timerResource.cancel();
              timerResource = null;
            }
          }
        };
        timerResource.schedule( resourceTask, maxDelay, maxDelay );
      } catch( IllegalStateException exc ) {
        System.err.println( "Illegal state exception" );
      }
    }
    lastModification = System.currentTimeMillis();
  }

  void registerServletWhenNotAlreadyRegistered() {
    if( !isApplicationRegistered ) {
      isApplicationRegistered = true;
      registerApplication();
    }
  }

  private void registerApplication() {
    ClassLoader loader = getContextClassloader();
    setContextClassloader();
    try {
      registerServlet();
    } catch( ServletException shouldNotHappen ) {
      throw new IllegalStateException( shouldNotHappen );
    } catch( NamespaceException shouldNotHappen ) {
      throw new IllegalStateException( shouldNotHappen );
    } finally {
      resetContextClassloader( loader );
    }
  }

  private ClassLoader getContextClassloader() {
    return Thread.currentThread().getContextClassLoader();
  }

  private void setContextClassloader() {
    Thread.currentThread().setContextClassLoader( getClass().getClassLoader() );
  }

  private void registerServlet() throws ServletException, NamespaceException {
    ClassLoader original = getContextClassloader();
    try {
      Thread.currentThread().setContextClassLoader( Application.class.getClassLoader() );
      httpService.registerServlet( rootPath, getServletContainer(), null, null );
    } finally {
      resetContextClassloader( original );
    }
  }

  private void resetContextClassloader( ClassLoader loader ) {
    Thread.currentThread().setContextClassLoader( loader );
  }

  public void removeResource( Object resource ) {
    getRootApplication().removeResource( resource );
    updateServletRegistration();
  }

  private void unregisterServletWhenNoresourcePresents() {
    if( !getRootApplication().hasResources() ) {
      if( httpService != null ) {
        httpService.unregister( rootPath );
      }
      servletContainer = new ServletContainer( ResourceConfig.forApplication( application ) );
      isApplicationRegistered = false;
    }
  }

  public List<Object> eliminate() {
    getServletContainer().destroy();
    try {
      httpService.unregister( rootPath );
    } catch( IllegalArgumentException iae ) {
      // do nothing
    }
    return new ArrayList<Object>( getRootApplication().getSingletons() );
  }

  // For testing purpose
  ServletContainer getServletContainer() {
    return servletContainer;
  }

  // For testing purpose
  RootApplication getRootApplication() {
    return application;
  }
}
