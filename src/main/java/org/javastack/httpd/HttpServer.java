package org.javastack.httpd;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class HttpServer {
	public static final String DIRECTORY_INDEX = "index.html";
	public static final int DEFAULT_ACCEPT_BACKLOG = 511;
	public static final int DEFAULT_ACCEPT_TIMEOUT = 1000;
	public static final int DEFAULT_READ_TIMEOUT = 60000;
	public static final boolean DEFAULT_TEST_ENABLE = true;
	public static final boolean DEFAULT_ACCESS_LOG_ENABLE = false;

	private static final byte[] ZIP_SIGNATURE = {
			0x50, 0x4b, 0x03, 0x04
	};
	private static final int BUFF_LEN = 512;
	private final SimpleDateFormat dateFormat;
	private final SimpleDateFormat accessFormat;
	private final SimpleDateFormat isoFormat;

	final ExecutorService pool = Executors.newCachedThreadPool();
	final AtomicBoolean running = new AtomicBoolean(false);
	final int port;
	final File baseDir;
	final ZipFile zipFile;

	int acceptBacklog = DEFAULT_ACCEPT_BACKLOG;
	int acceptTimeout = DEFAULT_ACCEPT_TIMEOUT;
	int readTimeout = DEFAULT_READ_TIMEOUT;
	boolean testEnabled = DEFAULT_TEST_ENABLE;
	boolean accessLogEnabled = DEFAULT_ACCESS_LOG_ENABLE;
	boolean debugLogEnabled = false;

	public static void main(final String[] args) throws Throwable {
		if (args.length < 2) {
			System.out.println(HttpServer.class.getName() + " <port> <directory|zipfile|none>");
			return;
		}
		final int port = Integer.parseInt(args[0]);
		final String dir = args[1];
		final HttpServer srv = new HttpServer(port, dir);
		srv.setTestEnabled(getProperty("httpd.test.enable", DEFAULT_TEST_ENABLE));
		srv.setAccessLogEnabled(getProperty("httpd.access.enable", DEFAULT_ACCESS_LOG_ENABLE));
		srv.setDebugLogEnabled(getProperty("httpd.debug.enable", false));
		srv.start();
	}

	public HttpServer(final int port, final String baseDir) throws IOException {
		this.port = port;
		this.baseDir = ("none".equals(baseDir) ? null : new File(baseDir).getCanonicalFile());
		this.zipFile = zipFile(this.baseDir);
		this.dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
		this.dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
		this.accessFormat = new SimpleDateFormat("[dd/MMM/yyyy:HH:mm:ss Z]");
		this.isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss,SSSXXX");
		this.testEnabled = ((baseDir == null) ? true : this.testEnabled);
	}

	public void start() {
		running.set(true);
		pool.submit(new Listener(port));
	}

	public void stop() {
		running.set(false);
		shutdownAndAwaitTermination(pool);
		Closer.close(zipFile);
	}

	public void setAcceptBacklog(final int acceptBacklog) {
		this.acceptBacklog = acceptBacklog;
	}

	public void setAcceptTimeoutMillis(final int acceptTimeout) {
		this.acceptTimeout = acceptTimeout;
	}

	public void setReadTimeoutMillis(final int readTimeout) {
		this.readTimeout = readTimeout;
	}

	public void setTestEnabled(final boolean testEnabled) {
		this.testEnabled = testEnabled;
	}

	public void setAccessLogEnabled(final boolean accessLogEnabled) {
		this.accessLogEnabled = accessLogEnabled;
	}

	public void setDebugLogEnabled(final boolean debugLogEnabled) {
		this.debugLogEnabled = debugLogEnabled;
	}

	String getHttpDate(final Date d) {
		synchronized (dateFormat) {
			return dateFormat.format(d);
		}
	}

	String getAccessDate(final Date d) {
		synchronized (accessFormat) {
			return accessFormat.format(d);
		}
	}

	String getIsoDate(final Date d) {
		synchronized (isoFormat) {
			return isoFormat.format(d);
		}
	}

	void debug(final String msg) {
		System.err.println(getIsoDate(new Date()) + " [DEBUG] " + msg);
	}

	private static boolean getProperty(final String name, final boolean def) {
		final String val = System.getProperty(name);
		if (val != null) {
			switch (val.trim().toUpperCase(Locale.US)) {
				case "TRUE":
					return true;
				case "FALSE":
					return false;
			}
		}
		// Undefined/Unknown > Default
		return def;
	}

	private static final char[] hexDigit = {
			'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
	};

	private static char toHex(final int nibble) {
		return hexDigit[(nibble & 0xF)];
	}

	private static final void escapeLog(final CharSequence in, final StringBuilder sb) {
		// Pseudo https://tools.ietf.org/html/rfc7159
		final int len = in.length();
		for (int i = 0; i < len; i++) {
			final char c = in.charAt(i);
			switch (c) {
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				// case '/':
				// sb.append("\\/");
				// break;
				case '\b':
					sb.append("\\b");
					break;
				case '\f':
					sb.append("\\f");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				default:
					if (((c < 0x0020) || (c > 0x007e))) {
						sb.append("\\u");
						sb.append(toHex(c >> 12));
						sb.append(toHex(c >> 8));
						sb.append(toHex(c >> 4));
						sb.append(toHex(c));
					} else {
						sb.append(c);
					}
			}
		}
	}

	StringBuilder access_begin(final String ip, final Date date, //
			final String method, String uri, String version) {
		final StringBuilder sb = new StringBuilder(256);
		sb // common-log
				.append(ip).append(' ') //
				.append("- -").append(' ') //
				.append(getAccessDate(date)).append(' ') //
				.append('"');
		escapeLog(method, sb);
		sb.append(' ');
		escapeLog(uri, sb);
		sb.append(' ');
		escapeLog(version, sb);
		sb.append("\" ");
		return sb;
	}

	void access_end(final StringBuilder sb, final int code, final long size) {
		if (sb == null) {
			return;
		}
		sb.append(code).append(' ');
		if (size < 0) {
			sb.append("-");
		} else {
			sb.append(size);
		}
		System.out.println(sb.toString());
	}

	ZipFile zipFile(final File file) throws ZipException, IOException {
		if (file == null) {
			return null;
		}
		if (file.isFile()) {
			FileInputStream is = null;
			try {
				final byte[] signature = new byte[ZIP_SIGNATURE.length];
				is = new FileInputStream(file);
				final int len = is.read(signature);
				if ((len == signature.length) && Arrays.equals(ZIP_SIGNATURE, signature)) {
					return new ZipFile(file);
				}
			} finally {
				Closer.close(is);
			}
		}
		return null;
	}

	boolean shutdownAndAwaitTermination(final ExecutorService pool) {
		pool.shutdown(); // Disable new tasks from being submitted
		try {
			// Wait a while for existing tasks to terminate
			if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
				pool.shutdownNow(); // Cancel currently executing tasks
				// Wait a while for tasks to respond to being cancelled
				if (!pool.awaitTermination(60, TimeUnit.SECONDS))
					return false;
			}
		} catch (InterruptedException ie) {
			// (Re-)Cancel if current thread also interrupted
			pool.shutdownNow();
			// Preserve interrupt status
			Thread.currentThread().interrupt();
		}
		return true;
	}

	class Listener implements Runnable {
		final int port;

		Listener(final int port) {
			this.port = port;
		}

		@Override
		public void run() {
			ServerSocket server = null;
			try {
				server = new ServerSocket();
				server.setSoTimeout(acceptTimeout);
				server.setReuseAddress(true);
				server.bind(new InetSocketAddress(port), acceptBacklog);
				if (debugLogEnabled) {
					debug("listener(start)=" + String.valueOf(server));
				}
				while (running.get()) {
					Socket client = null;
					try {
						client = server.accept();
						if (debugLogEnabled) {
							debug("client(accept)=" + String.valueOf(client));
						}
						client.setSoTimeout(readTimeout);
						pool.submit(new Connection(client));
					} catch (SocketTimeoutException e) {
						continue;
					} catch (Exception e) {
						e.printStackTrace(System.err);
						Closer.close(client);
					}
				}
			} catch (IOException e) {
				e.printStackTrace(System.err);
			} finally {
				if (debugLogEnabled) {
					debug("listener(end)=" + String.valueOf(server));
				}
				Closer.close(server);
			}
		}
	}

	class Connection implements Runnable {
		private static final String HDR_HTTP_VER = "HTTP/1.0";
		private static final String HDR_CACHE_CONTROL = "Cache-Control: private, max-age=0";
		private static final String HDR_CONNECTION_CLOSE = "Connection: close";
		private static final String HDR_SERVER = "Server: httpd";
		private static final String CRLF = "\r\n";

		final Socket client;

		Connection(final Socket client) {
			this.client = client;
		}

		@Override
		public void run() {
			BufferedReader in = null;
			PrintStream out = null;
			InputStream fis = null;
			long fisLength = 0;
			Date lastModified = null;
			StringBuilder access_log = null;
			try {
				if (debugLogEnabled) {
					debug("client(start)=" + String.valueOf(client));
				}
				in = new BufferedReader(new InputStreamReader(client.getInputStream()), BUFF_LEN);
				out = new PrintStream(new BufferedOutputStream(client.getOutputStream(), BUFF_LEN));
				// Read Head (GET / HTTP/1.0)
				final String header = in.readLine();
				final String[] hdrTokens = header.split(" ");
				final String METHOD = hdrTokens[0];
				final String URI = URLDecoder.decode(hdrTokens[1], "ISO-8859-1");
				final String VERSION = hdrTokens[2];
				// Read Headers
				while (!in.readLine().isEmpty()) {
					continue;
				}
				final boolean http10 = "HTTP/1.0".equals(VERSION);
				final boolean http11 = "HTTP/1.1".equals(VERSION);
				final boolean isGet = "GET".equals(METHOD);
				final boolean isHead = "HEAD".equals(METHOD);
				if (!http10 && !http11) {
					if (accessLogEnabled) {
						access_log = access_begin(client.getInetAddress().getHostAddress(), new Date(), //
								METHOD, URI, VERSION);
					}
					throw HttpError.HTTP_400;
				} else if (!isGet && !isHead) {
					if (accessLogEnabled) {
						access_log = access_begin(client.getInetAddress().getHostAddress(), new Date(), //
								METHOD, URI, VERSION);
					}
					throw HttpError.HTTP_405;
				} else {
					if (testEnabled && URI.equals("/test")) {
						sendResponse(out, 200, "OK", "OK\n", isHead);
						return;
					}
					if (accessLogEnabled) {
						access_log = access_begin(client.getInetAddress().getHostAddress(), new Date(), //
								METHOD, URI, VERSION);
					}
					if (zipFile != null) {
						lastModified = new Date(baseDir.lastModified());
						String zName = mapZipFile(URI);
						ZipEntry zipEntry = zipFile.getEntry(zName);
						if ((zipEntry != null) && zipEntry.isDirectory()) {
							zName = mapZipFile(URI + "/" + DIRECTORY_INDEX);
							zipEntry = zipFile.getEntry(zName);
						}
						if (zipEntry != null) {
							fisLength = zipEntry.getSize();
							fis = zipFile.getInputStream(zipEntry);
						}
					} else if (baseDir != null) {
						File f = new File(baseDir, URI).getCanonicalFile();
						if (!f.getPath().startsWith(baseDir.getPath())) {
							throw HttpError.HTTP_400;
						}
						if (f.isDirectory()) {
							f = new File(f, DIRECTORY_INDEX);
						}
						if (f.isFile()) {
							fisLength = f.length();
							fis = new FileInputStream(f);
							lastModified = new Date(f.lastModified());
						}
					}
					if (fis == null) {
						throw HttpError.HTTP_404;
					}
					sendFile(fis, out, fisLength, lastModified, isHead);
					access_end(access_log, 200, fisLength);
				}
			} catch (HttpError e) {
				sendError(out, e, e.getHttpText());
				access_end(access_log, e.getHttpCode(), -1);
			} catch (URISyntaxException e) {
				sendError(out, HttpError.HTTP_400, e.getMessage());
				access_end(access_log, 400, -1);
			} catch (SocketTimeoutException e) {
				sendError(out, HttpError.HTTP_408, e.getMessage());
				access_end(access_log, 408, -1);
			} catch (IOException e) {
				sendError(out, HttpError.HTTP_500, e.getMessage());
				access_end(access_log, 500, -1);
			} finally {
				if (debugLogEnabled) {
					debug("client(end)=" + String.valueOf(client));
				}
				Closer.close(fis);
				Closer.close(in);
				Closer.close(out);
				Closer.close(client);
			}
		}

		String mapZipFile(String uri) throws URISyntaxException {
			uri = new URI(uri).normalize().getPath();
			if ((uri.length() > 0) && (uri.charAt(0) == '/')) {
				uri = uri.substring(1);
			}
			if (uri.isEmpty() || uri.endsWith("/")) {
				return uri + DIRECTORY_INDEX;
			}
			return uri;
		}

		void sendFile(final InputStream is, final PrintStream out, //
				final long length, final Date lastModified, //
				final boolean isHead) throws IOException {
			out.append(HDR_HTTP_VER).append(" 200 OK").append(CRLF);
			out.append("Content-Length: ").append(String.valueOf(length)).append(CRLF);
			out.append("Date: ").append(getHttpDate(new Date())).append(CRLF);
			if (lastModified != null) {
				out.append("Last-Modified: ").append(getHttpDate(lastModified)).append(CRLF);
			}

			out.append(HDR_CACHE_CONTROL).append(CRLF);
			out.append(HDR_CONNECTION_CLOSE).append(CRLF);
			out.append(HDR_SERVER).append(CRLF);
			out.append(CRLF);
			//
			if (!isHead) {
				final byte[] buf = new byte[BUFF_LEN];
				int len = 0;
				while ((len = is.read(buf)) != -1) {
					out.write(buf, 0, len);
				}
			}
			out.flush();
		}

		void sendError(final PrintStream out, final HttpError e, final String body) {
			sendResponse(out, e.getHttpCode(), e.getHttpText(), body, false);
		}

		void sendResponse(final PrintStream out, final int code, final String text, //
				final String body, final boolean isHead) {
			out.append(HDR_HTTP_VER).append(' ').append(String.valueOf(code)).append(' ').append(text)
					.append(CRLF);
			out.append("Content-Length: ").append(String.valueOf(body.length())).append(CRLF);
			out.append("Content-Type: text/plain; charset=ISO-8859-1").append(CRLF);
			out.append(HDR_CACHE_CONTROL).append(CRLF);
			out.append(HDR_CONNECTION_CLOSE).append(CRLF);
			out.append(HDR_SERVER).append(CRLF);
			out.append(CRLF);
			if (!isHead) {
				out.append(body);
			}
			out.flush();
		}
	}

	static class HttpError extends Exception {
		public static final HttpError HTTP_400 = new HttpError(400, "Bad Request");
		public static final HttpError HTTP_404 = new HttpError(404, "Not Found");
		public static final HttpError HTTP_405 = new HttpError(405, "Method Not Allowed");
		public static final HttpError HTTP_408 = new HttpError(405, "Request Timeout");
		public static final HttpError HTTP_500 = new HttpError(500, "Internal Server Error");

		private static final long serialVersionUID = 42L;

		private final int code;
		private final String text;

		HttpError(final int code, final String text) {
			this.code = code;
			this.text = text;
		}

		public int getHttpCode() {
			return code;
		}

		public String getHttpText() {
			return text;
		}

		@Override
		public Throwable fillInStackTrace() {
			return this;
		}
	}

	static class Closer {
		static void close(final Closeable c) {
			if (c != null) {
				try {
					c.close();
				} catch (Exception ign) {
				}
			}
		}

		static void close(final ZipFile c) {
			if (c != null) {
				try {
					c.close();
				} catch (Exception ign) {
				}
			}
		}

		static void close(final Socket c) {
			if (c != null) {
				try {
					c.shutdownInput();
					c.shutdownOutput();
					c.close();
				} catch (Exception ign) {
				}
			}
		}

		static void close(final ServerSocket c) {
			if (c != null) {
				try {
					c.close();
				} catch (Exception ign) {
				}
			}
		}
	}
}
