/*
 * Copyright 2014-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.session.web.http;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.annotation.Order;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Switches the {@link javax.servlet.http.HttpSession} implementation to be backed by a
 * {@link org.springframework.session.Session}.
 *
 * The {@link SessionRepositoryFilter} wraps the
 * {@link javax.servlet.http.HttpServletRequest} and overrides the methods to get an
 * {@link javax.servlet.http.HttpSession} to be backed by a
 * {@link org.springframework.session.Session} returned by the
 * {@link org.springframework.session.SessionRepository}.
 *
 * The {@link SessionRepositoryFilter} uses a {@link HttpSessionIdResolver} (default
 * {@link CookieHttpSessionIdResolver}) to bridge logic between an
 * {@link javax.servlet.http.HttpSession} and the
 * {@link org.springframework.session.Session} abstraction. Specifically:
 *
 * <ul>
 * <li>The session id is looked up using
 * {@link HttpSessionIdResolver#resolveSessionIds(javax.servlet.http.HttpServletRequest)}
 * . The default is to look in a cookie named SESSION.</li>
 * <li>The session id of newly created {@link org.springframework.session.Session} is sent
 * to the client using
 * <li>The client is notified that the session id is no longer valid with
 * {@link HttpSessionIdResolver#expireSession(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)}
 * </li>
 * </ul>
 *
 * <p>
 * The SessionRepositoryFilter must be placed before any Filter that access the
 * HttpSession or that might commit the response to ensure the session is overridden and
 * persisted properly.
 * </p>
 *
 * @param <S> the {@link Session} type.
 * @since 1.0
 * @author Rob Winch
 * @author Vedran Pavic
 * @author Josh Cummings
 */
@Order(SessionRepositoryFilter.DEFAULT_ORDER)
public class SessionRepositoryFilter<S extends Session> extends OncePerRequestFilter {

	private static final String SESSION_LOGGER_NAME = SessionRepositoryFilter.class
			.getName().concat(".SESSION_LOGGER");

	private static final Log SESSION_LOGGER = LogFactory.getLog(SESSION_LOGGER_NAME);

	/**
	 * The session repository request attribute name.
	 */
	public static final String SESSION_REPOSITORY_ATTR = SessionRepository.class
			.getName();

	/**
	 * Invalid session id (not backed by the session repository) request attribute name.
	 */
	public static final String INVALID_SESSION_ID_ATTR = SESSION_REPOSITORY_ATTR
			+ ".invalidSessionId";

	private static final String CURRENT_SESSION_ATTR = SESSION_REPOSITORY_ATTR
			+ ".CURRENT_SESSION";

	/**
	 * The default filter order.
	 */
	public static final int DEFAULT_ORDER = Integer.MIN_VALUE + 50;

	private final SessionRepository<S> sessionRepository;

	private HttpSessionIdResolver httpSessionIdResolver = new CookieHttpSessionIdResolver();

	private boolean threadContextInheritable = false;

	/**
	 * Creates a new instance.
	 *
	 * @param sessionRepository the <code>SessionRepository</code> to use. Cannot be null.
	 */
	public SessionRepositoryFilter(SessionRepository<S> sessionRepository) {
		if (sessionRepository == null) {
			throw new IllegalArgumentException("sessionRepository cannot be null");
		}
		this.sessionRepository = sessionRepository;
	}

	/**
	 * Sets the {@link HttpSessionIdResolver} to be used. The default is a
	 * {@link CookieHttpSessionIdResolver}.
	 *
	 * @param httpSessionIdResolver the {@link HttpSessionIdResolver} to use. Cannot be
	 * null.
	 */
	public void setHttpSessionIdResolver(HttpSessionIdResolver httpSessionIdResolver) {
		if (httpSessionIdResolver == null) {
			throw new IllegalArgumentException("httpSessionIdResolver cannot be null");
		}
		this.httpSessionIdResolver = httpSessionIdResolver;
	}

	/**
	 * Set whether to expose the LocaleContext and RequestAttributes as inheritable
	 * for child threads (using an {@link java.lang.InheritableThreadLocal}).
	 * <p>Default is "false", to avoid side effects on spawned background threads.
	 * Switch this to "true" to enable inheritance for custom child threads which
	 * are spawned during request processing and only used for this request
	 * (that is, ending after their initial task, without reuse of the thread).
	 * <p><b>WARNING:</b> Do not use inheritance for child threads if you are
	 * accessing a thread pool which is configured to potentially add new threads
	 * on demand (e.g. a JDK {@link java.util.concurrent.ThreadPoolExecutor}),
	 * since this will expose the inherited context to such a pooled thread.
	 */
	public void setThreadContextInheritable(boolean threadContextInheritable) {
		this.threadContextInheritable = threadContextInheritable;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request,
			HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		request.setAttribute(SESSION_REPOSITORY_ATTR, this.sessionRepository);

		SessionRepositoryRequestWrapper wrappedRequest = new SessionRepositoryRequestWrapper(
				request, response);
		SessionRepositoryResponseWrapper wrappedResponse = new SessionRepositoryResponseWrapper(
				wrappedRequest, response);
		ServletRequestAttributes wrappedAttributes = new ServletRequestAttributes(
				wrappedRequest, wrappedResponse);
		initContextHolders(wrappedRequest, wrappedAttributes);

		try {
			filterChain.doFilter(wrappedRequest, wrappedResponse);
		}
		finally {
			wrappedRequest.commitSession();
			resetContextHolders();
			if (SESSION_LOGGER.isTraceEnabled()) {
				SESSION_LOGGER.trace("Cleared thread-bound request context: " + request);
			}
			wrappedAttributes.requestCompleted();
		}
	}

	private void initContextHolders(HttpServletRequest request, ServletRequestAttributes requestAttributes) {
		LocaleContextHolder.setLocale(request.getLocale(), this.threadContextInheritable);
		RequestContextHolder.setRequestAttributes(requestAttributes, this.threadContextInheritable);
		if (SESSION_LOGGER.isTraceEnabled()) {
			SESSION_LOGGER.trace("Bound request context to thread: " + request);
		}
	}

	private void resetContextHolders() {
		LocaleContextHolder.resetLocaleContext();
		RequestContextHolder.resetRequestAttributes();
	}

	/**
	 * Allows ensuring that the session is saved if the response is committed.
	 *
	 * @author Rob Winch
	 * @since 1.0
	 */
	private final class SessionRepositoryResponseWrapper
			extends OnCommittedResponseWrapper {

		private final SessionRepositoryRequestWrapper request;

		/**
		 * Create a new {@link SessionRepositoryResponseWrapper}.
		 * @param request the request to be wrapped
		 * @param response the response to be wrapped
		 */
		SessionRepositoryResponseWrapper(SessionRepositoryRequestWrapper request,
				HttpServletResponse response) {
			super(response);
			if (request == null) {
				throw new IllegalArgumentException("request cannot be null");
			}
			this.request = request;
		}

		@Override
		protected void onResponseCommitted() {
			this.request.commitSession();
		}
	}

	/**
	 * A {@link javax.servlet.http.HttpServletRequest} that retrieves the
	 * {@link javax.servlet.http.HttpSession} using a
	 * {@link org.springframework.session.SessionRepository}.
	 *
	 * @author Rob Winch
	 * @since 1.0
	 */
	private final class SessionRepositoryRequestWrapper
			extends HttpServletRequestWrapper {

		private final HttpServletResponse response;

		private S requestedSession;

		private boolean requestedSessionCached;

		private String requestedSessionId;

		private Boolean requestedSessionIdValid;

		private boolean requestedSessionInvalidated;

		private SessionRepositoryRequestWrapper(HttpServletRequest request,
				HttpServletResponse response) {
			super(request);
			this.response = response;
		}

		/**
		 * Uses the {@link HttpSessionIdResolver} to write the session id to the response
		 * and persist the Session.
		 */
		private void commitSession() {
			HttpSessionWrapper wrappedSession = getCurrentSession();
			if (wrappedSession == null) {
				if (isInvalidateClientSession()) {
					SessionRepositoryFilter.this.httpSessionIdResolver.expireSession(this,
							this.response);
				}
			}
			else {
				S session = wrappedSession.getSession();
				clearRequestedSessionCache();
				SessionRepositoryFilter.this.sessionRepository.save(session);
				String sessionId = session.getId();
				if (!isRequestedSessionIdValid()
						|| !sessionId.equals(getRequestedSessionId())) {
					SessionRepositoryFilter.this.httpSessionIdResolver.setSessionId(this,
							this.response, sessionId);
				}
			}
		}

		@SuppressWarnings("unchecked")
		private HttpSessionWrapper getCurrentSession() {
			return (HttpSessionWrapper) getAttribute(CURRENT_SESSION_ATTR);
		}

		private void setCurrentSession(HttpSessionWrapper currentSession) {
			if (currentSession == null) {
				removeAttribute(CURRENT_SESSION_ATTR);
			}
			else {
				setAttribute(CURRENT_SESSION_ATTR, currentSession);
			}
		}

		@Override
		@SuppressWarnings("unused")
		public String changeSessionId() {
			HttpSession session = getSession(false);

			if (session == null) {
				throw new IllegalStateException(
						"Cannot change session ID. There is no session associated with this request.");
			}

			return getCurrentSession().getSession().changeSessionId();
		}

		@Override
		public boolean isRequestedSessionIdValid() {
			if (this.requestedSessionIdValid == null) {
				S requestedSession = getRequestedSession();
				if (requestedSession != null) {
					requestedSession.setLastAccessedTime(Instant.now());
				}
				return isRequestedSessionIdValid(requestedSession);
			}
			return this.requestedSessionIdValid;
		}

		private boolean isRequestedSessionIdValid(S session) {
			if (this.requestedSessionIdValid == null) {
				this.requestedSessionIdValid = session != null;
			}
			return this.requestedSessionIdValid;
		}

		private boolean isInvalidateClientSession() {
			return getCurrentSession() == null && this.requestedSessionInvalidated;
		}

		@Override
		public HttpSessionWrapper getSession(boolean create) {
			HttpSessionWrapper currentSession = getCurrentSession();
			if (currentSession != null) {
				return currentSession;
			}
			S requestedSession = getRequestedSession();
			if (requestedSession != null) {
				if (getAttribute(INVALID_SESSION_ID_ATTR) == null) {
					requestedSession.setLastAccessedTime(Instant.now());
					this.requestedSessionIdValid = true;
					currentSession = new HttpSessionWrapper(requestedSession, getServletContext());
					currentSession.setNew(false);
					setCurrentSession(currentSession);
					return currentSession;
				}
			}
			else {
				// This is an invalid session id. No need to ask again if
				// request.getSession is invoked for the duration of this request
				if (SESSION_LOGGER.isDebugEnabled()) {
					SESSION_LOGGER.debug(
							"No session found by id: Caching result for getSession(false) for this HttpServletRequest.");
				}
				setAttribute(INVALID_SESSION_ID_ATTR, "true");
			}
			if (!create) {
				return null;
			}
			if (SESSION_LOGGER.isDebugEnabled()) {
				SESSION_LOGGER.debug(
						"A new session was created. To help you troubleshoot where the session was created we provided a StackTrace (this is not an error). You can prevent this from appearing by disabling DEBUG logging for "
								+ SESSION_LOGGER_NAME,
						new RuntimeException(
								"For debugging purposes only (not an error)"));
			}
			S session = SessionRepositoryFilter.this.sessionRepository.createSession();
			session.setLastAccessedTime(Instant.now());
			currentSession = new HttpSessionWrapper(session, getServletContext());
			setCurrentSession(currentSession);
			return currentSession;
		}

		@Override
		public HttpSessionWrapper getSession() {
			return getSession(true);
		}

		@Override
		public String getRequestedSessionId() {
			if (this.requestedSessionId == null) {
				getRequestedSession();
			}
			return this.requestedSessionId;
		}

		@Override
		public RequestDispatcher getRequestDispatcher(String path) {
			RequestDispatcher requestDispatcher = super.getRequestDispatcher(path);
			return new SessionCommittingRequestDispatcher(requestDispatcher);
		}

		private S getRequestedSession() {
			if (!this.requestedSessionCached) {
				List<String> sessionIds = SessionRepositoryFilter.this.httpSessionIdResolver
						.resolveSessionIds(this);
				for (String sessionId : sessionIds) {
					if (this.requestedSessionId == null) {
						this.requestedSessionId = sessionId;
					}
					S session = SessionRepositoryFilter.this.sessionRepository
							.findById(sessionId);
					if (session != null) {
						this.requestedSession = session;
						this.requestedSessionId = sessionId;
						break;
					}
				}
				this.requestedSessionCached = true;
			}
			return this.requestedSession;
		}

		private void clearRequestedSessionCache() {
			this.requestedSessionCached = false;
			this.requestedSession = null;
			this.requestedSessionId = null;
		}

		/**
		 * Allows creating an HttpSession from a Session instance.
		 *
		 * @author Rob Winch
		 * @since 1.0
		 */
		private final class HttpSessionWrapper extends HttpSessionAdapter<S> {

			HttpSessionWrapper(S session, ServletContext servletContext) {
				super(session, servletContext);
			}

			@Override
			public void invalidate() {
				super.invalidate();
				SessionRepositoryRequestWrapper.this.requestedSessionInvalidated = true;
				setCurrentSession(null);
				clearRequestedSessionCache();
				SessionRepositoryFilter.this.sessionRepository.deleteById(getId());
			}
		}

		/**
		 * Ensures session is committed before issuing an include.
		 *
		 * @since 1.3.4
		 */
		private final class SessionCommittingRequestDispatcher
				implements RequestDispatcher {

			private final RequestDispatcher delegate;

			SessionCommittingRequestDispatcher(RequestDispatcher delegate) {
				this.delegate = delegate;
			}

			@Override
			public void forward(ServletRequest request, ServletResponse response)
					throws ServletException, IOException {
				this.delegate.forward(request, response);
			}

			@Override
			public void include(ServletRequest request, ServletResponse response)
					throws ServletException, IOException {
				SessionRepositoryRequestWrapper.this.commitSession();
				this.delegate.include(request, response);
			}

		}

	}

}
