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
	public static final int DEFAULT_READ_TIMEOUT = 60000;
	private static final byte[] ZIP_SIGNATURE = {
			0x50, 0x4b, 0x03, 0x04
	};
	private static final int BUFF_LEN = 512;
	private final SimpleDateFormat dateFormat;

	final ExecutorService pool = Executors.newCachedThreadPool();
	final AtomicBoolean running = new AtomicBoolean(false);
	final int port;
	final File baseDir;
	final ZipFile zipFile;

	int readTimeout = DEFAULT_READ_TIMEOUT;

	public static void main(final String[] args) throws Throwable {
		if (args.length < 2) {
			System.out.println(HttpServer.class.getName() + " <port> <directory|zipfile>");
			return;
		}
		final int port = Integer.parseInt(args[0]);
		final String dir = args[1];
		final HttpServer srv = new HttpServer(port, dir);
		srv.start();
	}

	public HttpServer(final int port, final String baseDir) throws IOException {
		this.port = port;
		this.baseDir = new File(baseDir).getCanonicalFile();
		this.zipFile = zipFile(this.baseDir);
		this.dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
		this.dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
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

	public void setReadTimeoutMillis(final int readTimeout) {
		this.readTimeout = readTimeout;
	}

	ZipFile zipFile(final File file) throws ZipException, IOException {
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
				server.setSoTimeout(1000);
				server.setReuseAddress(true);
				server.bind(new InetSocketAddress(port));
				while (running.get()) {
					Socket client = null;
					try {
						client = server.accept();
						client.setSoTimeout(readTimeout);
						pool.submit(new Connection(client));
					} catch (SocketTimeoutException e) {
						continue;
					} catch (Exception e) {
						Closer.close(client);
					}
				}
			} catch (IOException e) {
				e.printStackTrace(System.out);
			} finally {
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
			try {
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
				if (!"HTTP/1.0".equals(VERSION) && !"HTTP/1.1".equals(VERSION)) {
					throw HttpError.HTTP_400;
				} else if (!"GET".equals(METHOD)) {
					throw HttpError.HTTP_405;
				} else {
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
					} else {
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
					sendFile(fis, out, fisLength, lastModified);
				}
			} catch (HttpError e) {
				sendError(out, e, e.getHttpText());
			} catch (URISyntaxException e) {
				sendError(out, HttpError.HTTP_400, e.getMessage());
			} catch (SocketTimeoutException e) {
				sendError(out, HttpError.HTTP_408, e.getMessage());
			} catch (IOException e) {
				sendError(out, HttpError.HTTP_500, e.getMessage());
			} finally {
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

		synchronized String getHttpDate(final Date d) {
			return dateFormat.format(d);
		}

		void sendFile(final InputStream is, final PrintStream out, long length, final Date lastModified)
				throws IOException {
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
			final byte[] buf = new byte[BUFF_LEN];
			int len = 0;
			while ((len = is.read(buf)) != -1) {
				out.write(buf, 0, len);
			}
			out.flush();
		}

		void sendError(final PrintStream out, final HttpError e, final String body) {
			sendError(out, e.getHttpCode(), e.getHttpText(), body);
		}

		void sendError(final PrintStream out, final int code, final String text, final String body) {
			out.append(HDR_HTTP_VER).append(' ').append(String.valueOf(code)).append(' ').append(text)
					.append(CRLF);
			out.append("Content-Length: ").append(String.valueOf(body.length())).append(CRLF);
			out.append("Content-Type: text/plain; charset=ISO-8859-1").append(CRLF);
			out.append(HDR_CACHE_CONTROL).append(CRLF);
			out.append(HDR_CONNECTION_CLOSE).append(CRLF);
			out.append(HDR_SERVER).append(CRLF);
			out.append(CRLF);
			out.append(body);
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
