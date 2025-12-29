/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Some of the code below is sourced from the Apache Tomcat fork of Apache commons-fileupload.
 * See https://github.com/apache/tomcat for the original.
 * It is also licensed under the terms of the Apache License, Version 2.0.
 */

package com.soklet;

import com.soklet.exception.IllegalRequestBodyException;
import com.soklet.exception.MissingRequestHeaderException;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.soklet.Utilities.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMultipartParser implements MultipartParser {
	@NonNull
	private static final Integer MAX_MULTIPART_FIELDS;
	@NonNull
	private static final DefaultMultipartParser DEFAULT_INSTANCE;

	static {
		MAX_MULTIPART_FIELDS = 1_000;
		DEFAULT_INSTANCE = new DefaultMultipartParser();
	}

	@NonNull
	public static DefaultMultipartParser defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	/**
	 * Validate boundary characters per RFC 2046.
	 */
	@NonNull
	private Boolean isValidBoundary(@NonNull String boundary) {
		requireNonNull(boundary);

		for (int i = 0; i < boundary.length(); i++) {
			char c = boundary.charAt(i);

			// Check if character is in allowed set
			boolean isAlphanumeric = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
			boolean isAllowedPunctuation = c == '\'' || c == '(' || c == ')' || c == '+' ||
					c == '_' || c == ',' || c == '-' || c == '.' ||
					c == '/' || c == ':' || c == '=' || c == '?';

			if (!isAlphanumeric && !isAllowedPunctuation)
				return false;
		}

		if (boundary.length() > 70)
			return false;

		return true;
	}

	@NonNull
	private String stripOptionalQuotes(@NonNull String value) {
		requireNonNull(value);

		value = value.trim();

		if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
			// RFC 7230 quoted-string: allow \" escapes
			String inner = value.substring(1, value.length() - 1);
			StringBuilder unescaped = new StringBuilder(inner.length());
			boolean escape = false;

			for (int i = 0; i < inner.length(); i++) {
				char c = inner.charAt(i);

				if (escape) {
					unescaped.append(c);
					escape = false;
				} else if (c == '\\') {
					escape = true;
				} else {
					unescaped.append(c);
				}
			}

			// If trailing backslash, keep it
			if (escape)
				unescaped.append('\\');

			return unescaped.toString();
		}

		return value;
	}

	@Override
	@NonNull
	public Map<@NonNull String, @NonNull Set<@NonNull MultipartField>> extractMultipartFields(@NonNull Request request) {
		byte[] requestBody = request.getBody().orElse(null);

		if (requestBody == null)
			return Map.of();

		// Required for embedded commons-upload code
		MultipartStream.ProgressNotifier progressNotifier = new MultipartStream.ProgressNotifier(new ProgressListener() {
			@Override
			public void update(long bytesRead, long contentLength, int items) {
				// Ignored for now
			}
		}, requestBody.length) {
			@Override
			void noteBytesRead(int pBytes) {
				// Ignored for now
			}

			@Override
			public void noteItem() {
				// Ignored for now
			}
		};

		String contentTypeHeader = request.getHeader("Content-Type").orElse(null);

		if (contentTypeHeader == null)
			throw new MissingRequestHeaderException("The 'Content-Type' header must be specified for multipart requests.", "Content-Type");

		Map<String, String> contentTypeHeaderFields = extractFields(contentTypeHeader);

		// Validate boundary before using it
		String boundary = trimAggressivelyToNull(contentTypeHeaderFields.get("boundary"));

		boundary = boundary == null ? null : trimAggressivelyToNull(stripOptionalQuotes(boundary));

		if (boundary == null)
			throw new IllegalRequestBodyException("Multipart request must include a non-empty 'boundary' parameter in Content-Type header");

		if (boundary.length() > 70)
			throw new IllegalRequestBodyException("Multipart request boundary exceeds maximum length of 70 (per RFC 2046)");

		if (!isValidBoundary(boundary))
			throw new IllegalRequestBodyException(
					format("Multipart request boundary contains illegal characters (per RFC 2046). " +
							"Allowed characters are: A-Z, a-z, 0-9, and '()+_,-./:=? - Boundary was: %s", boundary));

		Map<String, Set<MultipartField>> multipartFieldsByName = new LinkedHashMap<>();

		try (ByteArrayInputStream input = new ByteArrayInputStream(requestBody)) {
			MultipartStream multipartStream = new MultipartStream(input, boundary.getBytes(StandardCharsets.UTF_8), progressNotifier);

			int fieldCount = 0;
			boolean hasNext = multipartStream.skipPreamble();

			while (hasNext) {
				// Example headers:
				//
				// Content-Disposition: form-data; name="doc"; filename="test.pdf"
				// Content-Type: application/pdf
				// Use a case-insensitive map for simplified lookups
				Map<String, String> headers = splitHeaders(multipartStream.readHeaders());
				String contentDisposition = trimAggressivelyToNull(headers.get("Content-Disposition"));
				Map<String, String> contentDispositionFields = Map.of();

				if (contentDisposition != null)
					contentDispositionFields = new ParameterParser().parse(contentDisposition, ';');

				String name = trimAggressivelyToNull(contentDispositionFields.get("name"));

				if (name == null)
					continue;

				ByteArrayOutputStream data = new ByteArrayOutputStream();
				multipartStream.readBodyData(data);

				String filename = trimAggressivelyToNull(contentDispositionFields.get("filename"));

				// For example:
				// "Screenshot-1.53.26&#8239;PM.png"
				// becomes
				// "Screenshot-1.53.26 PM.png"
				if (filename != null)
					filename = HTMLUtilities.unescapeHtml(filename);

				String contentTypeHeaderValue = trimAggressivelyToNull(headers.get("Content-Type"));
				String contentType = Utilities.extractContentTypeFromHeaderValue(contentTypeHeaderValue).orElse(null);
				Charset charset = Utilities.extractCharsetFromHeaderValue(contentTypeHeaderValue).orElse(null);

				MultipartField multipartField = MultipartField.with(name, data.toByteArray())
						.filename(filename)
						.contentType(contentType)
						.charset(charset)
						.build();

				Set<MultipartField> multipartFields = multipartFieldsByName.get(name);

				if (multipartFields == null) {
					multipartFields = new LinkedHashSet<>();
					multipartFieldsByName.put(name, multipartFields);
				}

				multipartFields.add(multipartField);

				hasNext = multipartStream.readBoundary();

				if (++fieldCount > MAX_MULTIPART_FIELDS)
					throw new IllegalRequestBodyException(format("Too many multipart fields. Maximum allowed is %s", MAX_MULTIPART_FIELDS));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return multipartFieldsByName;
	}

	// The code below is sourced from Selenium.
	// It is licensed under the terms of the Apache License, Version 2.0.
	// The license text for all of the below code is as follows:

	/*
		Copyright 2012 Selenium committers
		Copyright 2012 Software Freedom Conservancy

		Licensed under the Apache License, Version 2.0 (the "License");
		you may not use this file except in compliance with the License.
		You may obtain a copy of the License at

		 http://www.apache.org/licenses/LICENSE-2.0

		Unless required by applicable law or agreed to in writing, software
		distributed under the License is distributed on an "AS IS" BASIS,
		WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
		See the License for the specific language governing permissions and
		limitations under the License.
	*/

	// *** START Selenium UploadFileHandler source ***

	protected LinkedCaseInsensitiveMap<String> splitHeaders(String readHeaders) {
		LinkedCaseInsensitiveMap<String> headersBuilder = new LinkedCaseInsensitiveMap<>();
		String[] headers = readHeaders.split("\r\n");
		for (String headerLine : headers) {
			int index = headerLine.indexOf(':');
			if (index < 0) {
				continue;
			}
			String key = headerLine.substring(0, index);
			String value = headerLine.substring(index + 1).trim();
			headersBuilder.put(key, value);
		}
		return headersBuilder;
	}

	protected LinkedCaseInsensitiveMap<String> extractFields(String contentTypeHeader) {
		LinkedCaseInsensitiveMap<String> fieldsBuilder = new LinkedCaseInsensitiveMap<>();
		if (contentTypeHeader == null)
			return fieldsBuilder;

		int separatorIndex = contentTypeHeader.indexOf(';');

		if (separatorIndex == -1)
			return fieldsBuilder;

		String parameters = contentTypeHeader.substring(separatorIndex + 1);
		parameters = trimAggressivelyToNull(parameters);

		if (parameters == null)
			return fieldsBuilder;

		ParameterParser parser = new ParameterParser();
		parser.setLowerCaseNames(true);

		Map<String, String> parsedParameters = parser.parse(parameters, ';');

		for (Map.Entry<String, String> entry : parsedParameters.entrySet()) {
			String key = trimAggressivelyToNull(entry.getKey());

			if (key == null)
				continue;

			fieldsBuilder.put(key, entry.getValue());
		}

		return fieldsBuilder;
	}

	// *** END Selenium UploadFileHandler source ***

	// The code below is sourced from the Apache Tomcat fork of Apache commons-fileupload.
	// See https://github.com/apache/tomcat for the original.
	// It is licensed under the terms of the Apache License, Version 2.0.
	// The license text for all of the below code is as follows:

	/*
	 * Licensed to the Apache Software Foundation (ASF) under one or more
	 * contributor license agreements.  See the NOTICE file distributed with
	 * this work for additional information regarding copyright ownership.
	 * The ASF licenses this file to You under the Apache License, Version 2.0
	 * (the "License"); you may not use this file except in compliance with
	 * the License.  You may obtain a copy of the License at
	 *
	 *      http://www.apache.org/licenses/LICENSE-2.0
	 *
	 * Unless required by applicable law or agreed to in writing, software
	 * distributed under the License is distributed on an "AS IS" BASIS,
	 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	 * See the License for the specific language governing permissions and
	 * limitations under the License.
	 */

	// *** START commons-fileupload source ***

	/**
	 * Receives progress information. May be used to display a progress bar.
	 */
	@FunctionalInterface
	protected interface ProgressListener {

		/**
		 * Nop implementation.
		 */
		ProgressListener NOP = (bytesRead, contentLength, items) -> {
			// nop
		};

		/**
		 * Updates the listeners status information.
		 *
		 * @param bytesRead     The total number of bytes, which have been read so far.
		 * @param contentLength The total number of bytes, which are being read. May be -1, if this number is unknown.
		 * @param items         The number of the field, which is currently being read. (0 = no item so far, 1 = first item is being read, ...)
		 */
		void update(long bytesRead, long contentLength, int items);

	}

	/**
	 * Exception for errors encountered while processing the request.
	 */
	protected static class FileUploadException extends IOException {

		private static final long serialVersionUID = -4222909057964038517L;

		/**
		 * Constructs a new {@code FileUploadException} without message.
		 */
		public FileUploadException() {
			super();
		}

		/**
		 * Constructs a new {@code FileUploadException} with specified detail
		 * message.
		 *
		 * @param msg the error message.
		 */
		public FileUploadException(final String msg) {
			super(msg);
		}

		/**
		 * Creates a new {@code FileUploadException} with the given
		 * detail message and cause.
		 *
		 * @param msg   The exceptions detail message.
		 * @param cause The exceptions cause.
		 */
		public FileUploadException(final String msg, final Throwable cause) {
			super(msg, cause);
		}
	}

	/**
	 * This exception is thrown for hiding an inner
	 * {@link FileUploadException} in an {@link IOException}.
	 */
	protected static class FileUploadIOException extends IOException {

		/**
		 * The exceptions UID, for serializing an instance.
		 */
		private static final long serialVersionUID = -7047616958165584154L;

		/**
		 * The exceptions cause; we overwrite the parent
		 * classes field, which is available since Java
		 * 1.4 only.
		 */
		private final FileUploadException cause;

		/**
		 * Creates a {@code FileUploadIOException} with the
		 * given cause.
		 *
		 * @param pCause The exceptions cause, if any, or null.
		 */
		public FileUploadIOException(final FileUploadException pCause) {
			// We're not doing super(pCause) cause of 1.3 compatibility.
			cause = pCause;
		}

		/**
		 * Returns the exceptions cause.
		 *
		 * @return The exceptions cause, if any, or null.
		 */
		@SuppressWarnings("sync-override") // Field is final
		@Override
		public Throwable getCause() {
			return cause;
		}

	}

	/**
	 * <p> This class provides support for accessing the headers for a file or form
	 * item that was received within a {@code multipart/form-data} POST
	 * request.</p>
	 *
	 * @since 1.2.1
	 */
	protected interface FileItemHeaders {

		/**
		 * Returns the value of the specified part header as a {@code String}.
		 * <p>
		 * If the part did not include a header of the specified name, this method
		 * return {@code null}.  If there are multiple headers with the same
		 * name, this method returns the first header in the item.  The header
		 * name is case insensitive.
		 *
		 * @param name a {@code String} specifying the header name
		 * @return a {@code String} containing the value of the requested
		 * header, or {@code null} if the item does not have a header
		 * of that name
		 */
		String getHeader(String name);

		/**
		 * <p>
		 * Returns all the values of the specified item header as an
		 * {@code Iterator} of {@code String} objects.
		 * </p>
		 * <p>
		 * If the item did not include any headers of the specified name, this
		 * method returns an empty {@code Iterator}. The header name is
		 * case insensitive.
		 * </p>
		 *
		 * @param name a {@code String} specifying the header name
		 * @return an {@code Iterator} containing the values of the
		 * requested header. If the item does not have any headers of
		 * that name, return an empty {@code Iterator}
		 */
		Iterator<String> getHeaders(String name);

		/**
		 * <p>
		 * Returns an {@code Iterator} of all the header names.
		 * </p>
		 *
		 * @return an {@code Iterator} containing all of the names of
		 * headers provided with this file item. If the item does not have
		 * any headers return an empty {@code Iterator}
		 */
		Iterator<String> getHeaderNames();

	}

	/**
	 * Interface that will indicate that FileItem or FileItemStream
	 * implementations will accept the headers read for the item.
	 *
	 * @see FileItemStream
	 * @since 1.2.1
	 */
	protected interface FileItemHeadersSupport {

		/**
		 * Returns the collection of headers defined locally within this item.
		 *
		 * @return the {@link FileItemHeaders} present for this item.
		 */
		FileItemHeaders getHeaders();

		/**
		 * Sets the headers read from within an item.  Implementations of
		 * FileItem or FileItemStream should implement this
		 * interface to be able to get the raw headers found within the item
		 * header block.
		 *
		 * @param headers the instance that holds onto the headers
		 *                for this instance.
		 */
		void setHeaders(FileItemHeaders headers);

	}

	/**
	 * <p> This interface provides access to a file or form item that was
	 * received within a {@code multipart/form-data} POST request.
	 * The items contents are retrieved by calling {@link #openStream()}.</p>
	 * <p>Instances of this class are created by accessing the
	 * iterator, returned by
	 * FileUploadBase#getItemIterator(RequestContext).</p>
	 * <p><em>Note</em>: There is an interaction between the iterator and
	 * its associated instances of {@link FileItemStream}: By invoking
	 * {@link java.util.Iterator#hasNext()} on the iterator, you discard all data,
	 * which hasn't been read so far from the previous data.</p>
	 */
	protected interface FileItemStream extends FileItemHeadersSupport {

		/**
		 * This exception is thrown, if an attempt is made to read
		 * data from the {@link InputStream}, which has been returned
		 * by {@link FileItemStream#openStream()}, after
		 * {@link java.util.Iterator#hasNext()} has been invoked on the
		 * iterator, which created the {@link FileItemStream}.
		 */
		class ItemSkippedException extends IOException {

			/**
			 * The exceptions serial version UID, which is being used
			 * when serializing an exception instance.
			 */
			private static final long serialVersionUID = -7280778431581963740L;

		}

		/**
		 * Creates an {@link InputStream}, which allows to read the
		 * items contents.
		 *
		 * @return The input stream, from which the items data may
		 * be read.
		 * @throws IllegalStateException The method was already invoked on
		 *                               this item. It is not possible to recreate the data stream.
		 * @throws IOException           An I/O error occurred.
		 * @see ItemSkippedException
		 */
		InputStream openStream() throws IOException;

		/**
		 * Returns the content type passed by the browser or {@code null} if
		 * not defined.
		 *
		 * @return The content type passed by the browser or {@code null} if
		 * not defined.
		 */
		String getContentType();

		/**
		 * Returns the original file name in the client's file system, as provided by
		 * the browser (or other client software). In most cases, this will be the
		 * base file name, without path information. However, some clients, such as
		 * the Opera browser, do include path information.
		 *
		 * @return The original file name in the client's file system.
		 */
		String getName();

		/**
		 * Returns the name of the field in the multipart form corresponding to
		 * this file item.
		 *
		 * @return The name of the form field.
		 */
		String getFieldName();

		/**
		 * Determines whether a {@code FileItem} instance represents a simple form field.
		 *
		 * @return {@code true} if the instance represents a simple form
		 * field; {@code false} if it represents an uploaded file.
		 */
		boolean isFormField();

	}

	/**
	 * This exception is thrown in case of an invalid file name.
	 * A file name is invalid, if it contains a NUL character.
	 * Attackers might use this to circumvent security checks:
	 * For example, a malicious user might upload a file with the name
	 * "foo.exe\0.png". This file name might pass security checks (i.e.
	 * checks for the extension ".png"), while, depending on the underlying
	 * C library, it might create a file named "foo.exe", as the NUL
	 * character is the string terminator in C.
	 */
	protected static class InvalidFileNameException extends RuntimeException {

		/**
		 * Serial version UID, being used, if the exception
		 * is serialized.
		 */
		private static final long serialVersionUID = 7922042602454350470L;

		/**
		 * The file name causing the exception.
		 */
		private final String name;

		/**
		 * Creates a new instance.
		 *
		 * @param pName    The file name causing the exception.
		 * @param pMessage A human readable error message.
		 */
		public InvalidFileNameException(final String pName, final String pMessage) {
			super(pMessage);
			name = pName;
		}

		/**
		 * Returns the invalid file name.
		 *
		 * @return the invalid file name.
		 */
		public String getName() {
			return name;
		}

	}

	/**
	 * Utility class for working with streams.
	 */
	protected static final class Streams {

		/**
		 * Private constructor, to prevent instantiation.
		 * This class has only static methods.
		 */
		private Streams() {
			// Does nothing
		}

		/**
		 * Default buffer size for use in
		 * {@link #copy(InputStream, OutputStream, boolean)}.
		 */
		public static final int DEFAULT_BUFFER_SIZE = 8192;

		/**
		 * Copies the contents of the given {@link InputStream}
		 * to the given {@link OutputStream}. Shortcut for
		 * <pre>
		 *   copy(pInputStream, pOutputStream, new byte[8192]);
		 * </pre>
		 *
		 * @param inputStream       The input stream, which is being read.
		 *                          It is guaranteed, that {@link InputStream#close()} is called
		 *                          on the stream.
		 * @param outputStream      The output stream, to which data should
		 *                          be written. May be null, in which case the input streams
		 *                          contents are simply discarded.
		 * @param closeOutputStream True guarantees, that {@link OutputStream#close()}
		 *                          is called on the stream. False indicates, that only
		 *                          {@link OutputStream#flush()} should be called finally.
		 * @return Number of bytes, which have been copied.
		 * @throws IOException An I/O error occurred.
		 */
		public static long copy(final InputStream inputStream, final OutputStream outputStream,
														final boolean closeOutputStream)
				throws IOException {
			return copy(inputStream, outputStream, closeOutputStream, new byte[DEFAULT_BUFFER_SIZE]);
		}

		/**
		 * Copies the contents of the given {@link InputStream}
		 * to the given {@link OutputStream}.
		 *
		 * @param inputStream       The input stream, which is being read.
		 *                          It is guaranteed, that {@link InputStream#close()} is called
		 *                          on the stream.
		 * @param outputStream      The output stream, to which data should
		 *                          be written. May be null, in which case the input streams
		 *                          contents are simply discarded.
		 * @param closeOutputStream True guarantees, that {@link OutputStream#close()}
		 *                          is called on the stream. False indicates, that only
		 *                          {@link OutputStream#flush()} should be called finally.
		 * @param buffer            Temporary buffer, which is to be used for
		 *                          copying data.
		 * @return Number of bytes, which have been copied.
		 * @throws IOException An I/O error occurred.
		 */
		public static long copy(final InputStream inputStream,
														final OutputStream outputStream, final boolean closeOutputStream,
														final byte[] buffer)
				throws IOException {
			try (OutputStream out = outputStream;
					 InputStream in = inputStream) {
				long total = 0;
				for (; ; ) {
					final int res = in.read(buffer);
					if (res == -1) {
						break;
					}
					if (res > 0) {
						total += res;
						if (out != null) {
							out.write(buffer, 0, res);
						}
					}
				}
				if (out != null) {
					if (closeOutputStream) {
						out.close();
					} else {
						out.flush();
					}
				}
				in.close();
				return total;
			}
		}

		/**
		 * Checks, whether the given file name is valid in the sense,
		 * that it doesn't contain any NUL characters. If the file name
		 * is valid, it will be returned without any modifications. Otherwise,
		 * an {@link InvalidFileNameException} is raised.
		 *
		 * @param fileName The file name to check
		 * @return Unmodified file name, if valid.
		 * @throws InvalidFileNameException The file name was found to be invalid.
		 */
		public static String checkFileName(final String fileName) {
			if (fileName != null && fileName.indexOf('\u0000') != -1) {
				// pFileName.replace("\u0000", "\\0")
				final StringBuilder sb = new StringBuilder();
				for (int i = 0; i < fileName.length(); i++) {
					final char c = fileName.charAt(i);
					switch (c) {
						case 0:
							sb.append("\\0");
							break;
						default:
							sb.append(c);
							break;
					}
				}
				throw new InvalidFileNameException(fileName,
						"Invalid file name: " + sb);
			}
			return fileName;
		}

	}

	/**
	 * <p> Low level API for processing file uploads.
	 *
	 * <p> This class can be used to process data streams conforming to MIME
	 * 'multipart' format as defined in
	 * <a href="http://www.ietf.org/rfc/rfc1867.txt">RFC 1867</a>. Arbitrarily
	 * large amounts of data in the stream can be processed under constant
	 * memory usage.
	 *
	 * <p> The format of the stream is defined in the following way:<br>
	 *
	 * <code>
	 * multipart-body := preamble 1*encapsulation close-delimiter epilogue<br>
	 * encapsulation := delimiter body CRLF<br>
	 * delimiter := "--" boundary CRLF<br>
	 * close-delimiter := "--" boundary "--"<br>
	 * preamble := &lt;ignore&gt;<br>
	 * epilogue := &lt;ignore&gt;<br>
	 * body := header-part CRLF body-part<br>
	 * header-part := 1*header CRLF<br>
	 * header := header-name ":" header-value<br>
	 * header-name := &lt;printable ascii characters except ":"&gt;<br>
	 * header-value := &lt;any ascii characters except CR &amp; LF&gt;<br>
	 * body-data := &lt;arbitrary data&gt;<br>
	 * </code>
	 *
	 * <p>Note that body-data can contain another mulipart entity.  There
	 * is limited support for single pass processing of such nested
	 * streams.  The nested stream is <strong>required</strong> to have a
	 * boundary token of the same length as the parent stream (see {@link
	 * #setBoundary(byte[])}).
	 *
	 * <p>Here is an example of usage of this class.<br>
	 *
	 * <pre>
	 *   try {
	 *     MultipartStream multipartStream = new MultipartStream(input, boundary);
	 *     boolean nextPart = multipartStream.skipPreamble();
	 *     OutputStream output;
	 *     while(nextPart) {
	 *       String header = multipartStream.readHeaders();
	 *       // process headers
	 *       // create some output stream
	 *       multipartStream.readBodyData(output);
	 *       nextPart = multipartStream.readBoundary();
	 *     }
	 *   } catch(MultipartStream.MalformedStreamException e) {
	 *     // the stream failed to follow required syntax
	 *   } catch(IOException e) {
	 *     // a read or write error occurred
	 *   }
	 * </pre>
	 */
	protected static class MultipartStream {
		/**
		 * Internal class, which is used to invoke the
		 * {@link ProgressListener}.
		 */
		public static class ProgressNotifier {

			/**
			 * The listener to invoke.
			 */
			private final ProgressListener listener;

			/**
			 * Number of expected bytes, if known, or -1.
			 */
			private final long contentLength;

			/**
			 * Number of bytes, which have been read so far.
			 */
			private long bytesRead;

			/**
			 * Number of items, which have been read so far.
			 */
			private int items;

			/**
			 * Creates a new instance with the given listener
			 * and content length.
			 *
			 * @param pListener      The listener to invoke.
			 * @param pContentLength The expected content length.
			 */
			public ProgressNotifier(final ProgressListener pListener, final long pContentLength) {
				listener = pListener;
				contentLength = pContentLength;
			}

			/**
			 * Called to indicate that bytes have been read.
			 *
			 * @param pBytes Number of bytes, which have been read.
			 */
			void noteBytesRead(final int pBytes) {
				/* Indicates, that the given number of bytes have been read from
				 * the input stream.
				 */
				bytesRead += pBytes;
				notifyListener();
			}

			/**
			 * Called to indicate, that a new file item has been detected.
			 */
			public void noteItem() {
				++items;
				notifyListener();
			}

			/**
			 * Called for notifying the listener.
			 */
			private void notifyListener() {
				if (listener != null) {
					listener.update(bytesRead, contentLength, items);
				}
			}

		}

		// ----------------------------------------------------- Manifest constants

		/**
		 * The Carriage Return ASCII character value.
		 */
		public static final byte CR = 0x0D;

		/**
		 * The Line Feed ASCII character value.
		 */
		public static final byte LF = 0x0A;

		/**
		 * The dash (-) ASCII character value.
		 */
		public static final byte DASH = 0x2D;

		/**
		 * The maximum length of {@code header-part} that will be
		 * processed (10 kilobytes = 10240 bytes.).
		 */
		public static final int HEADER_PART_SIZE_MAX = 10240;

		/**
		 * The default length of the buffer used for processing a request.
		 */
		protected static final int DEFAULT_BUFSIZE = 4096;

		/**
		 * A byte sequence that marks the end of {@code header-part}
		 * ({@code CRLFCRLF}).
		 */
		protected static final byte[] HEADER_SEPARATOR = {CR, LF, CR, LF};

		/**
		 * A byte sequence that that follows a delimiter that will be
		 * followed by an encapsulation ({@code CRLF}).
		 */
		protected static final byte[] FIELD_SEPARATOR = {CR, LF};

		/**
		 * A byte sequence that that follows a delimiter of the last
		 * encapsulation in the stream ({@code --}).
		 */
		protected static final byte[] STREAM_TERMINATOR = {DASH, DASH};

		/**
		 * A byte sequence that precedes a boundary ({@code CRLF--}).
		 */
		protected static final byte[] BOUNDARY_PREFIX = {CR, LF, DASH, DASH};

		// ----------------------------------------------------------- Data members

		/**
		 * The input stream from which data is read.
		 */
		private final InputStream input;

		/**
		 * The length of the boundary token plus the leading {@code CRLF--}.
		 */
		private int boundaryLength;

		/**
		 * The amount of data, in bytes, that must be kept in the buffer in order
		 * to detect delimiters reliably.
		 */
		private final int keepRegion;

		/**
		 * The byte sequence that partitions the stream.
		 */
		private final byte[] boundary;

		/**
		 * The table for Knuth-Morris-Pratt search algorithm.
		 */
		private final int[] boundaryTable;

		/**
		 * The length of the buffer used for processing the request.
		 */
		private final int bufSize;

		/**
		 * The buffer used for processing the request.
		 */
		private final byte[] buffer;

		/**
		 * The index of first valid character in the buffer.
		 * <br>
		 * 0 <= head < bufSize
		 */
		private int head;

		/**
		 * The index of last valid character in the buffer + 1.
		 * <br>
		 * 0 <= tail <= bufSize
		 */
		private int tail;

		/**
		 * The content encoding to use when reading headers.
		 */
		private String headerEncoding;

		/**
		 * The progress notifier, if any, or null.
		 */
		private final ProgressNotifier notifier;

		// ----------------------------------------------------------- Constructors

		/**
		 * <p> Constructs a {@code MultipartStream} with a custom size buffer.
		 *
		 * <p> Note that the buffer must be at least big enough to contain the
		 * boundary string, plus 4 characters for CR/LF and double dash, plus at
		 * least one byte of data.  Too small a buffer size setting will degrade
		 * performance.
		 *
		 * @param input     The {@code InputStream} to serve as a data source.
		 * @param boundary  The token used for dividing the stream into
		 *                  {@code encapsulations}.
		 * @param bufSize   The size of the buffer to be used, in bytes.
		 * @param pNotifier The notifier, which is used for calling the
		 *                  progress listener, if any.
		 * @throws IllegalArgumentException If the buffer size is too small
		 * @since 1.3.1
		 */
		public MultipartStream(final InputStream input,
													 final byte[] boundary,
													 final int bufSize,
													 final ProgressNotifier pNotifier) {

			if (boundary == null) {
				throw new IllegalArgumentException("boundary may not be null");
			}
			// We prepend CR/LF to the boundary to chop trailing CR/LF from
			// body-data tokens.
			this.boundaryLength = boundary.length + BOUNDARY_PREFIX.length;
			if (bufSize < this.boundaryLength + 1) {
				throw new IllegalArgumentException(
						"The buffer size specified for the MultipartStream is too small");
			}

			this.input = input;
			this.bufSize = Math.max(bufSize, boundaryLength * 2);
			this.buffer = new byte[this.bufSize];
			this.notifier = pNotifier;

			this.boundary = new byte[this.boundaryLength];
			this.boundaryTable = new int[this.boundaryLength + 1];
			this.keepRegion = this.boundary.length;

			System.arraycopy(BOUNDARY_PREFIX, 0, this.boundary, 0,
					BOUNDARY_PREFIX.length);
			System.arraycopy(boundary, 0, this.boundary, BOUNDARY_PREFIX.length,
					boundary.length);
			computeBoundaryTable();

			head = 0;
			tail = 0;
		}

		/**
		 * <p> Constructs a {@code MultipartStream} with a default size buffer.
		 *
		 * @param input     The {@code InputStream} to serve as a data source.
		 * @param boundary  The token used for dividing the stream into
		 *                  {@code encapsulations}.
		 * @param pNotifier An object for calling the progress listener, if any.
		 * @see #MultipartStream(InputStream, byte[], int, ProgressNotifier)
		 */
		public MultipartStream(final InputStream input,
													 final byte[] boundary,
													 final ProgressNotifier pNotifier) {
			this(input, boundary, DEFAULT_BUFSIZE, pNotifier);
		}

		// --------------------------------------------------------- Public methods

		/**
		 * Retrieves the character encoding used when reading the headers of an
		 * individual part. When not specified, or {@code null}, the platform
		 * default encoding is used.
		 *
		 * @return The encoding used to read part headers.
		 */
		public String getHeaderEncoding() {
			return headerEncoding;
		}

		/**
		 * Specifies the character encoding to be used when reading the headers of
		 * individual parts. When not specified, or {@code null}, the platform
		 * default encoding is used.
		 *
		 * @param encoding The encoding used to read part headers.
		 */
		public void setHeaderEncoding(final String encoding) {
			headerEncoding = encoding;
		}

		/**
		 * Reads a byte from the {@code buffer}, and refills it as
		 * necessary.
		 *
		 * @return The next byte from the input stream.
		 * @throws IOException if there is no more data available.
		 */
		public byte readByte() throws IOException {
			// Buffer depleted ?
			if (head == tail) {
				head = 0;
				// Refill.
				tail = input.read(buffer, head, bufSize);
				if (tail == -1) {
					// No more data available.
					throw new IOException("No more data is available");
				}
				if (notifier != null) {
					notifier.noteBytesRead(tail);
				}
			}
			return buffer[head++];
		}

		/**
		 * Skips a {@code boundary} token, and checks whether more
		 * {@code encapsulations} are contained in the stream.
		 *
		 * @return {@code true} if there are more encapsulations in
		 * this stream; {@code false} otherwise.
		 * @throws FileUploadIOException    if the bytes read from the stream exceeded the size limits
		 * @throws MalformedStreamException if the stream ends unexpectedly or
		 *                                  fails to follow required syntax.
		 */
		public boolean readBoundary()
				throws FileUploadIOException, MalformedStreamException {
			final byte[] marker = new byte[2];
			final boolean nextChunk;

			head += boundaryLength;
			try {
				marker[0] = readByte();
				if (marker[0] == LF) {
					// Work around IE5 Mac bug with input type=image.
					// Because the boundary delimiter, not including the trailing
					// CRLF, must not appear within any file (RFC 2046, section
					// 5.1.1), we know the missing CR is due to a buggy browser
					// rather than a file containing something similar to a
					// boundary.
					return true;
				}

				marker[1] = readByte();
				if (arrayequals(marker, STREAM_TERMINATOR, 2)) {
					nextChunk = false;
				} else if (arrayequals(marker, FIELD_SEPARATOR, 2)) {
					nextChunk = true;
				} else {
					throw new MalformedStreamException(
							"Unexpected characters follow a boundary");
				}
			} catch (final FileUploadIOException e) {
				// wraps a SizeException, re-throw as it will be unwrapped later
				throw e;
			} catch (final IOException e) {
				throw new MalformedStreamException("Stream ended unexpectedly");
			}
			return nextChunk;
		}

		/**
		 * <p>Changes the boundary token used for partitioning the stream.
		 *
		 * <p>This method allows single pass processing of nested multipart
		 * streams.
		 *
		 * <p>The boundary token of the nested stream is {@code required}
		 * to be of the same length as the boundary token in parent stream.
		 *
		 * <p>Restoring the parent stream boundary token after processing of a
		 * nested stream is left to the application.
		 *
		 * @param boundary The boundary to be used for parsing of the nested
		 *                 stream.
		 * @throws IllegalBoundaryException if the {@code boundary}
		 *                                  has a different length than the one
		 *                                  being currently parsed.
		 */
		public void setBoundary(final byte[] boundary)
				throws IllegalBoundaryException {
			if (boundary.length != boundaryLength - BOUNDARY_PREFIX.length) {
				throw new IllegalBoundaryException(
						"The length of a boundary token cannot be changed");
			}
			System.arraycopy(boundary, 0, this.boundary, BOUNDARY_PREFIX.length,
					boundary.length);
			computeBoundaryTable();
		}

		/**
		 * Compute the table used for Knuth-Morris-Pratt search algorithm.
		 */
		private void computeBoundaryTable() {
			int position = 2;
			int candidate = 0;

			boundaryTable[0] = -1;
			boundaryTable[1] = 0;

			while (position <= boundaryLength) {
				if (boundary[position - 1] == boundary[candidate]) {
					boundaryTable[position] = candidate + 1;
					candidate++;
					position++;
				} else if (candidate > 0) {
					candidate = boundaryTable[candidate];
				} else {
					boundaryTable[position] = 0;
					position++;
				}
			}
		}

		/**
		 * <p>Reads the {@code header-part} of the current
		 * {@code encapsulation}.
		 *
		 * <p>Headers are returned verbatim to the input stream, including the
		 * trailing {@code CRLF} marker. Parsing is left to the
		 * application.
		 *
		 * @return The {@code header-part} of the current encapsulation.
		 * @throws FileUploadIOException    if the bytes read from the stream exceeded the size limits.
		 * @throws MalformedStreamException if the stream ends unexpectedly.
		 */
		public String readHeaders() throws FileUploadIOException, MalformedStreamException {
			int i = 0;
			byte b;
			// to support multi-byte characters
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			int size = 0;
			while (i < HEADER_SEPARATOR.length) {
				try {
					b = readByte();
				} catch (final FileUploadIOException e) {
					// wraps a SizeException, re-throw as it will be unwrapped later
					throw e;
				} catch (final IOException e) {
					throw new MalformedStreamException("Stream ended unexpectedly");
				}
				if (++size > HEADER_PART_SIZE_MAX) {
					throw new MalformedStreamException(format(
							"Header section has more than %s bytes (maybe it is not properly terminated)",
							Integer.valueOf(HEADER_PART_SIZE_MAX)));
				}
				if (b == HEADER_SEPARATOR[i]) {
					i++;
				} else {
					i = 0;
				}
				baos.write(b);
			}

			String headers;
			if (headerEncoding != null) {
				try {
					headers = baos.toString(headerEncoding);
				} catch (final UnsupportedEncodingException e) {
					// Fall back to platform default if specified encoding is not
					// supported.
					headers = baos.toString();
				}
			} else {
				headers = baos.toString();
			}

			return headers;
		}

		/**
		 * <p>Reads {@code body-data} from the current
		 * {@code encapsulation} and writes its contents into the
		 * output {@code Stream}.
		 *
		 * <p>Arbitrary large amounts of data can be processed by this
		 * method using a constant size buffer. (see {@link
		 * #MultipartStream(InputStream, byte[], int,
		 * MultipartStream.ProgressNotifier) constructor}).
		 *
		 * @param output The {@code Stream} to write data into. May
		 *               be null, in which case this method is equivalent
		 *               to {@link #discardBodyData()}.
		 * @return the amount of data written.
		 * @throws MalformedStreamException if the stream ends unexpectedly.
		 * @throws IOException              if an i/o error occurs.
		 */
		public int readBodyData(final OutputStream output)
				throws MalformedStreamException, IOException {
			return (int) Streams.copy(newInputStream(), output, false); // N.B. Streams.copy closes the input stream
		}

		/**
		 * Creates a new {@link ItemInputStream}.
		 *
		 * @return A new instance of {@link ItemInputStream}.
		 */
		public ItemInputStream newInputStream() {
			return new ItemInputStream();
		}

		/**
		 * <p> Reads {@code body-data} from the current
		 * {@code encapsulation} and discards it.
		 *
		 * <p>Use this method to skip encapsulations you don't need or don't
		 * understand.
		 *
		 * @return The amount of data discarded.
		 * @throws MalformedStreamException if the stream ends unexpectedly.
		 * @throws IOException              if an i/o error occurs.
		 */
		public int discardBodyData() throws MalformedStreamException, IOException {
			return readBodyData(null);
		}

		/**
		 * Finds the beginning of the first {@code encapsulation}.
		 *
		 * @return {@code true} if an {@code encapsulation} was found in
		 * the stream.
		 * @throws IOException if an i/o error occurs.
		 */
		public boolean skipPreamble() throws IOException {
			// First delimiter may be not preceded with a CRLF.
			System.arraycopy(boundary, 2, boundary, 0, boundary.length - 2);
			boundaryLength = boundary.length - 2;
			computeBoundaryTable();
			try {
				// Discard all data up to the delimiter.
				discardBodyData();

				// Read boundary - if succeeded, the stream contains an
				// encapsulation.
				return readBoundary();
			} catch (final MalformedStreamException e) {
				return false;
			} finally {
				// Restore delimiter.
				System.arraycopy(boundary, 0, boundary, 2, boundary.length - 2);
				boundaryLength = boundary.length;
				boundary[0] = CR;
				boundary[1] = LF;
				computeBoundaryTable();
			}
		}

		/**
		 * Compares {@code count} first bytes in the arrays
		 * {@code a} and {@code b}.
		 *
		 * @param a     The first array to compare.
		 * @param b     The second array to compare.
		 * @param count How many bytes should be compared.
		 * @return {@code true} if {@code count} first bytes in arrays
		 * {@code a} and {@code b} are equal.
		 */
		public static boolean arrayequals(final byte[] a,
																			final byte[] b,
																			final int count) {
			for (int i = 0; i < count; i++) {
				if (a[i] != b[i]) {
					return false;
				}
			}
			return true;
		}

		/**
		 * Searches for the {@code boundary} in the {@code buffer}
		 * region delimited by {@code head} and {@code tail}.
		 *
		 * @return The position of the boundary found, counting from the
		 * beginning of the {@code buffer}, or {@code -1} if
		 * not found.
		 */
		protected int findSeparator() {

			int bufferPos = this.head;
			int tablePos = 0;

			while (bufferPos < this.tail) {
				while (tablePos >= 0 && buffer[bufferPos] != boundary[tablePos]) {
					tablePos = boundaryTable[tablePos];
				}
				bufferPos++;
				tablePos++;
				if (tablePos == boundaryLength) {
					return bufferPos - boundaryLength;
				}
			}
			return -1;
		}

		/**
		 * Thrown to indicate that the input stream fails to follow the
		 * required syntax.
		 */
		public static class MalformedStreamException extends IOException {

			/**
			 * The UID to use when serializing this instance.
			 */
			private static final long serialVersionUID = 6466926458059796677L;

			/**
			 * Constructs a {@code MalformedStreamException} with no
			 * detail message.
			 */
			public MalformedStreamException() {
			}

			/**
			 * Constructs an {@code MalformedStreamException} with
			 * the specified detail message.
			 *
			 * @param message The detail message.
			 */
			public MalformedStreamException(final String message) {
				super(message);
			}

		}

		/**
		 * Thrown upon attempt of setting an invalid boundary token.
		 */
		public static class IllegalBoundaryException extends IOException {

			/**
			 * The UID to use when serializing this instance.
			 */
			private static final long serialVersionUID = -161533165102632918L;

			/**
			 * Constructs an {@code IllegalBoundaryException} with no
			 * detail message.
			 */
			public IllegalBoundaryException() {
			}

			/**
			 * Constructs an {@code IllegalBoundaryException} with
			 * the specified detail message.
			 *
			 * @param message The detail message.
			 */
			public IllegalBoundaryException(final String message) {
				super(message);
			}

		}

		/**
		 * An {@link InputStream} for reading an items contents.
		 */
		public class ItemInputStream extends InputStream implements Closeable {

			/**
			 * The number of bytes, which have been read so far.
			 */
			private long total;

			/**
			 * The number of bytes, which must be hold, because
			 * they might be a part of the boundary.
			 */
			private int pad;

			/**
			 * The current offset in the buffer.
			 */
			private int pos;

			/**
			 * Whether the stream is already closed.
			 */
			private boolean closed;

			/**
			 * Creates a new instance.
			 */
			ItemInputStream() {
				findSeparator();
			}

			/**
			 * Called for finding the separator.
			 */
			private void findSeparator() {
				pos = MultipartStream.this.findSeparator();
				if (pos == -1) {
					if (tail - head > keepRegion) {
						pad = keepRegion;
					} else {
						pad = tail - head;
					}
				}
			}

			/**
			 * Returns the number of bytes, which have been read
			 * by the stream.
			 *
			 * @return Number of bytes, which have been read so far.
			 */
			public long getBytesRead() {
				return total;
			}

			/**
			 * Returns the number of bytes, which are currently
			 * available, without blocking.
			 *
			 * @return Number of bytes in the buffer.
			 * @throws IOException An I/O error occurs.
			 */
			@Override
			public int available() throws IOException {
				if (pos == -1) {
					return tail - head - pad;
				}
				return pos - head;
			}

			/**
			 * Offset when converting negative bytes to integers.
			 */
			private static final int BYTE_POSITIVE_OFFSET = 256;

			/**
			 * Returns the next byte in the stream.
			 *
			 * @return The next byte in the stream, as a non-negative
			 * integer, or -1 for EOF.
			 * @throws IOException An I/O error occurred.
			 */
			@Override
			public int read() throws IOException {
				if (closed) {
					throw new FileItemStream.ItemSkippedException();
				}
				if (available() == 0 && makeAvailable() == 0) {
					return -1;
				}
				++total;
				final int b = buffer[head++];
				if (b >= 0) {
					return b;
				}
				return b + BYTE_POSITIVE_OFFSET;
			}

			/**
			 * Reads bytes into the given buffer.
			 *
			 * @param b   The destination buffer, where to write to.
			 * @param off Offset of the first byte in the buffer.
			 * @param len Maximum number of bytes to read.
			 * @return Number of bytes, which have been actually read,
			 * or -1 for EOF.
			 * @throws IOException An I/O error occurred.
			 */
			@Override
			public int read(final byte[] b, final int off, final int len) throws IOException {
				if (closed) {
					throw new FileItemStream.ItemSkippedException();
				}
				if (len == 0) {
					return 0;
				}
				int res = available();
				if (res == 0) {
					res = makeAvailable();
					if (res == 0) {
						return -1;
					}
				}
				res = Math.min(res, len);
				System.arraycopy(buffer, head, b, off, res);
				head += res;
				total += res;
				return res;
			}

			/**
			 * Closes the input stream.
			 *
			 * @throws IOException An I/O error occurred.
			 */
			@Override
			public void close() throws IOException {
				close(false);
			}

			/**
			 * Closes the input stream.
			 *
			 * @param pCloseUnderlying Whether to close the underlying stream
			 *                         (hard close)
			 * @throws IOException An I/O error occurred.
			 */
			public void close(final boolean pCloseUnderlying) throws IOException {
				if (closed) {
					return;
				}
				if (pCloseUnderlying) {
					closed = true;
					input.close();
				} else {
					for (; ; ) {
						int av = available();
						if (av == 0) {
							av = makeAvailable();
							if (av == 0) {
								break;
							}
						}
						skip(av);
					}
				}
				closed = true;
			}

			/**
			 * Skips the given number of bytes.
			 *
			 * @param bytes Number of bytes to skip.
			 * @return The number of bytes, which have actually been
			 * skipped.
			 * @throws IOException An I/O error occurred.
			 */
			@Override
			public long skip(final long bytes) throws IOException {
				if (closed) {
					throw new FileItemStream.ItemSkippedException();
				}
				int av = available();
				if (av == 0) {
					av = makeAvailable();
					if (av == 0) {
						return 0;
					}
				}
				final long res = Math.min(av, bytes);
				head += res;
				return res;
			}

			/**
			 * Attempts to read more data.
			 *
			 * @return Number of available bytes
			 * @throws IOException An I/O error occurred.
			 */
			private int makeAvailable() throws IOException {
				if (pos != -1) {
					return 0;
				}

				// Move the data to the beginning of the buffer.
				total += tail - head - pad;
				System.arraycopy(buffer, tail - pad, buffer, 0, pad);

				// Refill buffer with new data.
				head = 0;
				tail = pad;

				for (; ; ) {
					final int bytesRead = input.read(buffer, tail, bufSize - tail);
					if (bytesRead == -1) {
						// The last pad amount is left in the buffer.
						// Boundary can't be in there so signal an error
						// condition.
						final String msg = "Stream ended unexpectedly";
						throw new MalformedStreamException(msg);
					}
					if (notifier != null) {
						notifier.noteBytesRead(bytesRead);
					}
					tail += bytesRead;

					findSeparator();
					final int av = available();

					if (av > 0 || pos != -1) {
						return av;
					}
				}
			}

			/**
			 * Returns, whether the stream is closed.
			 *
			 * @return True, if the stream is closed, otherwise false.
			 */
			public boolean isClosed() {
				return closed;
			}

		}
	}

	/**
	 * A simple parser intended to parse sequences of name/value pairs.
	 * <p>
	 * Parameter values are expected to be enclosed in quotes if they contain unsafe characters, such as '=' characters or separators. Parameter values are optional
	 * and can be omitted.
	 * </p>
	 * <p>
	 * {@code param1 = value; param2 = "anything goes; really"; param3}
	 * </p>
	 */
	protected class ParameterParser {

		/**
		 * String to be parsed.
		 */
		private char[] chars = null;

		/**
		 * Current position in the string.
		 */
		private int pos = 0;

		/**
		 * Maximum position in the string.
		 */
		private int len = 0;

		/**
		 * Start of a token.
		 */
		private int i1 = 0;

		/**
		 * End of a token.
		 */
		private int i2 = 0;

		/**
		 * Whether names stored in the map should be converted to lower case.
		 */
		private boolean lowerCaseNames = false;

		/**
		 * Default ParameterParser constructor.
		 */
		public ParameterParser() {
		}

		/**
		 * A helper method to process the parsed token. This method removes leading and trailing blanks as well as enclosing quotation marks, when necessary.
		 *
		 * @param quoted {@code true} if quotation marks are expected, {@code false} otherwise.
		 * @return the token
		 */
		private String getToken(final boolean quoted) {
			// Trim leading white spaces
			while (i1 < i2 && Character.isWhitespace(chars[i1])) {
				i1++;
			}
			// Trim trailing white spaces
			while (i2 > i1 && Character.isWhitespace(chars[i2 - 1])) {
				i2--;
			}
			// Strip away quotation marks if necessary
			if (quoted && i2 - i1 >= 2 && chars[i1] == '"' && chars[i2 - 1] == '"') {
				i1++;
				i2--;
			}
			String result = null;
			if (i2 > i1) {
				result = new String(chars, i1, i2 - i1);
			}
			return result;
		}

		/**
		 * Tests if there any characters left to parse.
		 *
		 * @return {@code true} if there are unparsed characters, {@code false} otherwise.
		 */
		private boolean hasChar() {
			return this.pos < this.len;
		}

		/**
		 * Tests {@code true} if parameter names are to be converted to lower case when name/value pairs are parsed.
		 *
		 * @return {@code true} if parameter names are to be converted to lower case when name/value pairs are parsed. Otherwise returns {@code false}
		 */
		public boolean isLowerCaseNames() {
			return this.lowerCaseNames;
		}

		/**
		 * Tests if the given character is present in the array of characters.
		 *
		 * @param ch      the character to test for presence in the array of characters
		 * @param charray the array of characters to test against
		 * @return {@code true} if the character is present in the array of characters, {@code false} otherwise.
		 */
		private boolean isOneOf(final char ch, final char[] charray) {
			var result = false;
			for (final char element : charray) {
				if (ch == element) {
					result = true;
					break;
				}
			}
			return result;
		}

		/**
		 * Parses a map of name/value pairs from the given array of characters. Names are expected to be unique.
		 *
		 * @param charArray the array of characters that contains a sequence of name/value pairs
		 * @param separator the name/value pairs separator
		 * @return a map of name/value pairs
		 */
		public Map<String, String> parse(final char[] charArray, final char separator) {
			if (charArray == null) {
				return new LinkedHashMap<>();
			}
			return parse(charArray, 0, charArray.length, separator);
		}

		/**
		 * Parses a map of name/value pairs from the given array of characters. Names are expected to be unique.
		 *
		 * @param charArray the array of characters that contains a sequence of name/value pairs
		 * @param offset    - the initial offset.
		 * @param length    - the length.
		 * @param separator the name/value pairs separator
		 * @return a map of name/value pairs
		 */
		public Map<String, String> parse(final char[] charArray, final int offset, final int length, final char separator) {

			if (charArray == null) {
				return new LinkedHashMap<>();
			}
			final var params = new LinkedHashMap<String, String>();
			this.chars = charArray.clone();
			this.pos = offset;
			this.len = length;

			String paramName;
			String paramValue;
			while (hasChar()) {
				paramName = parseToken(new char[]{'=', separator});
				paramValue = null;
				if (hasChar() && charArray[pos] == '=') {
					pos++; // skip '='
					paramValue = parseQuotedToken(new char[]{separator});

					if (paramValue != null) {
						try {
							paramValue = RFC2231Utils.hasEncodedValue(paramName) ? RFC2231Utils.decodeText(paramValue) : MimeUtils.decodeText(paramValue);
						} catch (final UnsupportedEncodingException ignored) {
							// let's keep the original value in this case
						}
					}
				}
				if (hasChar() && charArray[pos] == separator) {
					pos++; // skip separator
				}
				if (paramName != null && !paramName.isEmpty()) {
					paramName = RFC2231Utils.stripDelimiter(paramName);
					if (this.lowerCaseNames) {
						paramName = paramName.toLowerCase(Locale.ENGLISH);
					}
					params.put(paramName, paramValue);
				}
			}
			return params;
		}

		/**
		 * Parses a map of name/value pairs from the given string. Names are expected to be unique.
		 *
		 * @param str       the string that contains a sequence of name/value pairs
		 * @param separator the name/value pairs separator
		 * @return a map of name/value pairs
		 */
		public Map<String, String> parse(final String str, final char separator) {
			if (str == null) {
				return new LinkedHashMap<>();
			}
			return parse(str.toCharArray(), separator);
		}

		/**
		 * Parses a map of name/value pairs from the given string. Names are expected to be unique. Multiple separators may be specified and the earliest found in
		 * the input string is used.
		 *
		 * @param str        the string that contains a sequence of name/value pairs
		 * @param separators the name/value pairs separators
		 * @return a map of name/value pairs
		 */
		public Map<String, String> parse(final String str, final char[] separators) {
			if (separators == null || separators.length == 0) {
				return new LinkedHashMap<>();
			}
			var separator = separators[0];
			if (str != null) {
				var idx = str.length();
				for (final char separator2 : separators) {
					final var tmp = str.indexOf(separator2);
					if (tmp != -1 && tmp < idx) {
						idx = tmp;
						separator = separator2;
					}
				}
			}
			return parse(str, separator);
		}

		/**
		 * Parses out a token until any of the given terminators is encountered outside the quotation marks.
		 *
		 * @param terminators the array of terminating characters. Any of these characters when encountered outside the quotation marks signify the end of the token
		 * @return the token
		 */
		private String parseQuotedToken(final char[] terminators) {
			char ch;
			i1 = pos;
			i2 = pos;
			var quoted = false;
			var charEscaped = false;
			while (hasChar()) {
				ch = chars[pos];
				if (!quoted && isOneOf(ch, terminators)) {
					break;
				}
				if (!charEscaped && ch == '"') {
					quoted = !quoted;
				}
				charEscaped = !charEscaped && ch == '\\';
				i2++;
				pos++;

			}
			return getToken(true);
		}

		/**
		 * Parses out a token until any of the given terminators is encountered.
		 *
		 * @param terminators the array of terminating characters. Any of these characters when encountered signify the end of the token
		 * @return the token
		 */
		private String parseToken(final char[] terminators) {
			char ch;
			i1 = pos;
			i2 = pos;
			while (hasChar()) {
				ch = chars[pos];
				if (isOneOf(ch, terminators)) {
					break;
				}
				i2++;
				pos++;
			}
			return getToken(false);
		}

		/**
		 * Sets the flag if parameter names are to be converted to lower case when name/value pairs are parsed.
		 *
		 * @param lowerCaseNames {@code true} if parameter names are to be converted to lower case when name/value pairs are parsed. {@code false} otherwise.
		 */
		public void setLowerCaseNames(final boolean lowerCaseNames) {
			this.lowerCaseNames = lowerCaseNames;
		}

	}

	/**
	 * Utility class to decode/encode character set on HTTP Header fields based on RFC 2231. This implementation adheres to RFC 5987 in particular, which was
	 * defined for HTTP headers
	 * <p>
	 * RFC 5987 builds on RFC 2231, but has lesser scope like <a href="https://tools.ietf.org/html/rfc5987#section-3.2">mandatory charset definition</a> and
	 * <a href="https://tools.ietf.org/html/rfc5987#section-4">no parameter continuation</a>
	 * </p>
	 *
	 * @see <a href="https://tools.ietf.org/html/rfc2231">RFC 2231</a>
	 * @see <a href="https://tools.ietf.org/html/rfc5987">RFC 5987</a>
	 */
	protected final class RFC2231Utils {

		/**
		 * The Hexadecimal values char array.
		 */
		private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();
		/**
		 * The Hexadecimal representation of 127.
		 */
		private static final byte MASK = 0x7f;
		/**
		 * The Hexadecimal representation of 128.
		 */
		private static final int MASK_128 = 0x80;
		/**
		 * The Hexadecimal decode value.
		 */
		private static final byte[] HEX_DECODE = new byte[MASK_128];

		// create a ASCII decoded array of Hexadecimal values
		static {
			for (var i = 0; i < HEX_DIGITS.length; i++) {
				HEX_DECODE[HEX_DIGITS[i]] = (byte) i;
				HEX_DECODE[Character.toLowerCase(HEX_DIGITS[i])] = (byte) i;
			}
		}

		/**
		 * Decodes a string of text obtained from a HTTP header as per RFC 2231
		 *
		 * <b>Eg 1.</b> {@code us-ascii'en-us'This%20is%20%2A%2A%2Afun%2A%2A%2A} will be decoded to {@code This is ***fun***}
		 *
		 * <b>Eg 2.</b> {@code iso-8859-1'en'%A3%20rate} will be decoded to {@code £ rate}.
		 *
		 * <b>Eg 3.</b> {@code UTF-8''%c2%a3%20and%20%e2%82%ac%20rates} will be decoded to {@code £ and € rates}.
		 *
		 * @param encodedText - Text to be decoded has a format of {@code <charset>'<language>'<encoded_value>} and ASCII only
		 * @return Decoded text based on charset encoding
		 * @throws UnsupportedEncodingException The requested character set wasn't found.
		 */
		static String decodeText(final String encodedText) throws UnsupportedEncodingException {
			final var langDelimitStart = encodedText.indexOf('\'');
			if (langDelimitStart == -1) {
				// missing charset
				return encodedText;
			}
			final var mimeCharset = encodedText.substring(0, langDelimitStart);
			final var langDelimitEnd = encodedText.indexOf('\'', langDelimitStart + 1);
			if (langDelimitEnd == -1) {
				// missing language
				return encodedText;
			}
			final var bytes = fromHex(encodedText.substring(langDelimitEnd + 1));
			return new String(bytes, getJavaCharset(mimeCharset));
		}

		/**
		 * Converts {@code text} to their corresponding Hex value.
		 *
		 * @param text - ASCII text input
		 * @return Byte array of characters decoded from ASCII table
		 */
		private static byte[] fromHex(final String text) {
			final var shift = 4;
			final var out = new ByteArrayOutputStream(text.length());
			for (var i = 0; i < text.length(); ) {
				final var c = text.charAt(i++);
				if (c == '%') {
					if (i > text.length() - 2) {
						break; // unterminated sequence
					}
					final var b1 = HEX_DECODE[text.charAt(i++) & MASK];
					final var b2 = HEX_DECODE[text.charAt(i++) & MASK];
					out.write(b1 << shift | b2);
				} else {
					out.write((byte) c);
				}
			}
			return out.toByteArray();
		}

		private static String getJavaCharset(final String mimeCharset) {
			// good enough for standard values
			return mimeCharset;
		}

		/**
		 * Tests if asterisk (*) at the end of parameter name to indicate, if it has charset and language information to decode the value.
		 *
		 * @param paramName The parameter, which is being checked.
		 * @return {@code true}, if encoded as per RFC 2231, {@code false} otherwise
		 */
		static boolean hasEncodedValue(final String paramName) {
			if (paramName != null) {
				return paramName.lastIndexOf('*') == paramName.length() - 1;
			}
			return false;
		}

		/**
		 * If {@code paramName} has Asterisk (*) at the end, it will be stripped off, else the passed value will be returned.
		 *
		 * @param paramName The parameter, which is being inspected.
		 * @return stripped {@code paramName} of Asterisk (*), if RFC2231 encoded
		 */
		static String stripDelimiter(final String paramName) {
			if (hasEncodedValue(paramName)) {
				final var paramBuilder = new StringBuilder(paramName);
				paramBuilder.deleteCharAt(paramName.lastIndexOf('*'));
				return paramBuilder.toString();
			}
			return paramName;
		}

		/**
		 * Private constructor so that no instances can be created. This class contains only static utility methods.
		 */
		private RFC2231Utils() {
		}
	}

	/**
	 * Utility class to decode MIME texts.
	 */
	protected final class MimeUtils {

		/**
		 * The marker to indicate text is encoded with BASE64 algorithm.
		 */
		private static final String BASE64_ENCODING_MARKER = "B";

		/**
		 * The marker to indicate text is encoded with QuotedPrintable algorithm.
		 */
		private static final String QUOTEDPRINTABLE_ENCODING_MARKER = "Q";

		/**
		 * If the text contains any encoded tokens, those tokens will be marked with "=?".
		 */
		private static final String ENCODED_TOKEN_MARKER = "=?";

		/**
		 * If the text contains any encoded tokens, those tokens will terminate with "=?".
		 */
		private static final String ENCODED_TOKEN_FINISHER = "?=";

		/**
		 * The linear whitespace chars sequence.
		 */
		private static final String LINEAR_WHITESPACE = " \t\r\n";

		/**
		 * Mappings between MIME and Java charset.
		 */
		private static final Map<String, String> MIME2JAVA = new HashMap<>();

		static {
			MIME2JAVA.put("iso-2022-cn", "ISO2022CN");
			MIME2JAVA.put("iso-2022-kr", "ISO2022KR");
			MIME2JAVA.put("utf-8", "UTF8");
			MIME2JAVA.put("utf8", "UTF8");
			MIME2JAVA.put("ja_jp.iso2022-7", "ISO2022JP");
			MIME2JAVA.put("ja_jp.eucjp", "EUCJIS");
			MIME2JAVA.put("euc-kr", "KSC5601");
			MIME2JAVA.put("euckr", "KSC5601");
			MIME2JAVA.put("us-ascii", "ISO-8859-1");
			MIME2JAVA.put("x-us-ascii", "ISO-8859-1");
		}

		/**
		 * Decodes a string of text obtained from a mail header into its proper form. The text generally will consist of a string of tokens, some of which may be
		 * encoded using base64 encoding.
		 *
		 * @param text The text to decode.
		 * @return The decoded text string.
		 * @throws UnsupportedEncodingException if the detected encoding in the input text is not supported.
		 */
		static String decodeText(final String text) throws UnsupportedEncodingException {
			// if the text contains any encoded tokens, those tokens will be marked with "=?". If the
			// source string doesn't contain that sequent, no decoding is required.
			if (!text.contains(ENCODED_TOKEN_MARKER)) {
				return text;
			}

			var offset = 0;
			final var endOffset = text.length();

			var startWhiteSpace = -1;
			var endWhiteSpace = -1;

			final var decodedText = new StringBuilder(text.length());

			var previousTokenEncoded = false;

			while (offset < endOffset) {
				var ch = text.charAt(offset);

				// is this a whitespace character?
				if (LINEAR_WHITESPACE.indexOf(ch) != -1) { // whitespace found
					startWhiteSpace = offset;
					while (offset < endOffset) {
						// step over the white space characters.
						ch = text.charAt(offset);
						if (LINEAR_WHITESPACE.indexOf(ch) == -1) {
							// record the location of the first non lwsp and drop down to process the
							// token characters.
							endWhiteSpace = offset;
							break;
						}
						offset++;
					}
				} else {
					// we have a word token. We need to scan over the word and then try to parse it.
					final var wordStart = offset;

					while (offset < endOffset) {
						// step over the non white space characters.
						ch = text.charAt(offset);
						if (LINEAR_WHITESPACE.indexOf(ch) != -1) {
							break;
						}
						offset++;

						// NB: Trailing whitespace on these header strings will just be discarded.
					}
					// pull out the word token.
					final var word = text.substring(wordStart, offset);
					// is the token encoded? decode the word
					if (word.startsWith(ENCODED_TOKEN_MARKER)) {
						try {
							// if this gives a parsing failure, treat it like a non-encoded word.
							final var decodedWord = decodeWord(word);

							// are any whitespace characters significant? Append 'em if we've got 'em.
							if (!previousTokenEncoded && startWhiteSpace != -1) {
								decodedText.append(text, startWhiteSpace, endWhiteSpace);
								startWhiteSpace = -1;
							}
							// this is definitely a decoded token.
							previousTokenEncoded = true;
							// and add this to the text.
							decodedText.append(decodedWord);
							// we continue parsing from here...we allow parsing errors to fall through
							// and get handled as normal text.
							continue;

						} catch (final ParseException ignored) {
							// just ignore it, skip to next word
						}
					}
					// this is a normal token, so it doesn't matter what the previous token was. Add the white space
					// if we have it.
					if (startWhiteSpace != -1) {
						decodedText.append(text, startWhiteSpace, endWhiteSpace);
						startWhiteSpace = -1;
					}
					// this is not a decoded token.
					previousTokenEncoded = false;
					decodedText.append(word);
				}
			}

			return decodedText.toString();
		}

		/**
		 * Decodes a string using the RFC 2047 rules for an "encoded-word" type. This encoding has the syntax:
		 * <p>
		 * encoded-word = "=?" charset "?" encoding "?" encoded-text "?="
		 *
		 * @param word The possibly encoded word value.
		 * @return The decoded word.
		 * @throws ParseException               in case of a parse error of the RFC 2047.
		 * @throws UnsupportedEncodingException Thrown when Invalid RFC 2047 encoding was found.
		 */
		private static String decodeWord(final String word) throws ParseException, UnsupportedEncodingException {
			// encoded words start with the characters "=?". If this not an encoded word, we throw a
			// ParseException for the caller.

			final var etmPos = word.indexOf(ENCODED_TOKEN_MARKER);
			if (etmPos != 0) {
				throw new ParseException("Invalid RFC 2047 encoded-word: " + word, etmPos);
			}

			final var charsetPos = word.indexOf('?', 2);
			if (charsetPos == -1) {
				throw new ParseException("Missing charset in RFC 2047 encoded-word: " + word, charsetPos);
			}

			// pull out the character set information (this is the MIME name at this point).
			final var charset = word.substring(2, charsetPos).toLowerCase(Locale.ENGLISH);

			// now pull out the encoding token the same way.
			final var encodingPos = word.indexOf('?', charsetPos + 1);
			if (encodingPos == -1) {
				throw new ParseException("Missing encoding in RFC 2047 encoded-word: " + word, encodingPos);
			}

			final var encoding = word.substring(charsetPos + 1, encodingPos);

			// and finally the encoded text.
			final var encodedTextPos = word.indexOf(ENCODED_TOKEN_FINISHER, encodingPos + 1);
			if (encodedTextPos == -1) {
				throw new ParseException("Missing encoded text in RFC 2047 encoded-word: " + word, encodedTextPos);
			}

			final var encodedText = word.substring(encodingPos + 1, encodedTextPos);

			// seems a bit silly to encode a null string, but easy to deal with.
			if (encodedText.isEmpty()) {
				return "";
			}

			try {
				// the decoder writes directly to an output stream.
				final var out = new ByteArrayOutputStream(encodedText.length());

				final var encodedData = encodedText.getBytes(StandardCharsets.US_ASCII);

				// Base64 encoded?
				if (encoding.equals(BASE64_ENCODING_MARKER)) {
					out.write(Base64.getMimeDecoder().decode(encodedData));
				} else if (encoding.equals(QUOTEDPRINTABLE_ENCODING_MARKER)) { // maybe quoted printable.
					QuotedPrintableDecoder.decode(encodedData, out);
				} else {
					throw new UnsupportedEncodingException("Unknown RFC 2047 encoding: " + encoding);
				}
				// get the decoded byte data and convert into a string.
				final var decodedData = out.toByteArray();
				return new String(decodedData, javaCharset(charset));
			} catch (final IOException e) {
				throw new UnsupportedEncodingException("Invalid RFC 2047 encoding");
			}
		}

		/**
		 * Translate a MIME standard character set name into the Java equivalent.
		 *
		 * @param charset The MIME standard name.
		 * @return The Java equivalent for this name.
		 */
		private static String javaCharset(final String charset) {
			// nothing in, nothing out.
			if (charset == null) {
				return null;
			}
			final var mappedCharset = MIME2JAVA.get(charset.toLowerCase(Locale.ENGLISH));
			// if there is no mapping, then the original name is used. Many of the MIME character set
			// names map directly back into Java. The reverse isn't necessarily true.
			return mappedCharset == null ? charset : mappedCharset;
		}

		/**
		 * Hidden constructor, this class must not be instantiated.
		 */
		private MimeUtils() {
			// do nothing
		}

	}

	protected final class QuotedPrintableDecoder {

		/**
		 * The shift value required to create the upper nibble from the first of 2 byte values converted from ASCII hex.
		 */
		private static final int UPPER_NIBBLE_SHIFT = Byte.SIZE / 2;

		/**
		 * Decodes the encoded byte data writing it to the given output stream.
		 *
		 * @param data The array of byte data to decode.
		 * @param out  The output stream used to return the decoded data.
		 * @return the number of bytes produced.
		 * @throws IOException if an IO error occurs
		 */
		public static int decode(final byte[] data, final OutputStream out) throws IOException {
			var off = 0;
			final var length = data.length;
			final var endOffset = off + length;
			var bytesWritten = 0;

			while (off < endOffset) {
				final var ch = data[off++];

				// space characters were translated to '_' on encode, so we need to translate them back.
				if (ch == '_') {
					out.write(' ');
				} else if (ch == '=') {
					// we found an encoded character. Reduce the 3 char sequence to one.
					// but first, make sure we have two characters to work with.
					if (off + 1 >= endOffset) {
						throw new IOException("Invalid quoted printable encoding; truncated escape sequence");
					}

					final var b1 = data[off++];
					final var b2 = data[off++];

					// we've found an encoded carriage return. The next char needs to be a newline
					if (b1 == '\r') {
						if (b2 != '\n') {
							throw new IOException("Invalid quoted printable encoding; CR must be followed by LF");
						}
						// this was a soft linebreak inserted by the encoding. We just toss this away
						// on decode.
					} else {
						// this is a hex pair we need to convert back to a single byte.
						final var c1 = hexToBinary(b1);
						final var c2 = hexToBinary(b2);
						out.write(c1 << UPPER_NIBBLE_SHIFT | c2);
						// 3 bytes in, one byte out
						bytesWritten++;
					}
				} else {
					// simple character, just write it out.
					out.write(ch);
					bytesWritten++;
				}
			}

			return bytesWritten;
		}

		/**
		 * Converts a hexadecimal digit to the binary value it represents.
		 *
		 * @param b the ASCII hexadecimal byte to convert (0-0, A-F, a-f)
		 * @return the int value of the hexadecimal byte, 0-15
		 * @throws IOException if the byte is not a valid hexadecimal digit.
		 */
		private static int hexToBinary(final byte b) throws IOException {
			// CHECKSTYLE IGNORE MagicNumber FOR NEXT 1 LINE
			final var i = Character.digit((char) b, 16);
			if (i == -1) {
				throw new IOException("Invalid quoted printable encoding: not a valid hex digit: " + b);
			}
			return i;
		}

		/**
		 * Hidden constructor, this class must not be instantiated.
		 */
		private QuotedPrintableDecoder() {
			// do nothing
		}

	}

	// *** END commons-fileupload source ***

	// For HTML-Unescaper below, see https://gist.github.com/MarkJeronimus/798c452582e64410db769933ec71cfb7

	// *** START HTML-Unescaper source ***

	/**
	 * HTML Un-escaper by Nick Frolov.
	 * <p>
	 * With improvement suggested by Axel Dörfler.
	 * <p>
	 * Replaced character map with HTML5 characters from<a href="https://www.w3schools.com/charsets/ref_html_entities_a.asp">
	 * https://www.w3schools.com/charsets/ref_html_entities_a.asp</a>
	 *
	 * @author Nick Frolov, Mark Jeronimus
	 */
// Created 2020-06-22
	protected static class HTMLUtilities {
		// Tables optimized for smallest .class size (without resorting to compression)
		private static final String[] NAMES =
				{"excl", "quot", "num", "dollar", "percnt", "amp", "apos", "lpar", "rpar", "ast", "midast", "plus", "comma",
						"period", "sol", "colon", "semi", "lt", "equals", "GT", "quest", "commat", "lbrack", "lsqb", "bsol",
						"rbrack", "rsqb", "Hat", "lowbar", "UnderBar", "DiacriticalGrave", "grave", "lbrace", "lcub", "verbar",
						"vert", "VerticalLine", "rbrace", "rcub", "nbsp", "NonBreakingSpace", "iexcl", "cent", "pound", "curren",
						"yen", "brvbar", "sect", "die", "Dot", "DoubleDot", "uml", "copy", "ordf", "laquo", "not", "shy",
						"circledR", "reg", "macr", "strns", "deg", "plusmn", "pm", "sup2", "sup3", "acute", "DiacriticalAcute",
						"micro", "para", "CenterDot", "centerdot", "middot", "cedil", "Cedilla", "sup1", "ordm", "raquo", "frac14",
						"frac12", "half", "frac34", "iquest", "Agrave", "Aacute", "Acirc", "Atilde", "Auml", "angst", "Aring",
						"AElig", "Ccedil", "Egrave", "Eacute", "Ecirc", "Euml", "Igrave", "Iacute", "Icirc", "Iuml", "ETH",
						"Ntilde", "Ograve", "Oacute", "Ocirc", "Otilde", "Ouml", "times", "Oslash", "Ugrave", "Uacute", "Ucirc",
						"Uuml", "Yacute", "THORN", "szlig", "agrave", "aacute", "acirc", "atilde", "auml", "aring", "aelig",
						"ccedil", "egrave", "eacute", "ecirc", "euml", "igrave", "iacute", "icirc", "iuml", "eth", "ntilde",
						"ograve", "oacute", "ocirc", "otilde", "ouml", "div", "divide", "oslash", "ugrave", "uacute", "ucirc",
						"uuml", "yacute", "thorn", "yuml", "Amacr", "amacr", "Abreve", "abreve", "Aogon", "aogon", "Cacute",
						"cacute", "Ccirc", "ccirc", "Cdot", "cdot", "Ccaron", "ccaron", "Dcaron", "dcaron", "Dstrok", "dstrok",
						"Emacr", "emacr", "Edot", "edot", "Eogon", "eogon", "Ecaron", "ecaron", "Gcirc", "gcirc", "Gbreve",
						"gbreve", "Gdot", "gdot", "Gcedil", "Hcirc", "hcirc", "Hstrok", "hstrok", "Itilde", "itilde", "Imacr",
						"imacr", "Iogon", "iogon", "Idot", "imath", "inodot", "IJlig", "ijlig", "Jcirc", "jcirc", "Kcedil",
						"kcedil", "kgreen", "Lacute", "lacute", "Lcedil", "lcedil", "Lcaron", "lcaron", "Lmidot", "lmidot",
						"Lstrok", "lstrok", "Nacute", "nacute", "Ncedil", "ncedil", "Ncaron", "ncaron", "napos", "ENG", "eng",
						"Omacr", "omacr", "Odblac", "odblac", "OElig", "oelig", "Racute", "racute", "Rcedil", "rcedil", "Rcaron",
						"rcaron", "Sacute", "sacute", "Scirc", "scirc", "Scedil", "scedil", "Scaron", "scaron", "Tcedil", "tcedil",
						"Tcaron", "tcaron", "Tstrok", "tstrok", "Utilde", "utilde", "Umacr", "umacr", "Ubreve", "ubreve", "Uring",
						"uring", "Udblac", "udblac", "Uogon", "uogon", "Wcirc", "wcirc", "Ycirc", "ycirc", "Yuml", "Zacute",
						"zacute", "Zdot", "zdot", "Zcaron", "zcaron", "fnof", "imped", "gacute", "jmath", "circ", "caron", "Hacek",
						"Breve", "breve", "DiacriticalDot", "dot", "ring", "ogon", "DiacriticalTilde", "tilde", "dblac",
						"DiacriticalDoubleAcute", "DownBreve", "Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta", "Eta",
						"Theta", "Iota", "Kappa", "Lambda", "Mu", "Nu", "Xi", "Omicron", "Pi", "Rho", "Sigma", "Tau", "Upsilon",
						"Phi", "Chi", "Psi", "ohm", "Omega", "alpha", "beta", "gamma", "delta", "epsi", "epsilon", "zeta", "eta",
						"theta", "iota", "kappa", "lambda", "mu", "nu", "xi", "omicron", "pi", "rho", "sigmaf", "sigmav",
						"varsigma", "sigma", "tau", "upsi", "upsilon", "phi", "chi", "psi", "omega", "thetasym", "thetav",
						"vartheta", "Upsi", "upsih", "phiv", "straightphi", "varphi", "piv", "varpi", "Gammad", "digamma",
						"gammad", "kappav", "varkappa", "rhov", "varrho", "epsiv", "straightepsilon", "varepsilon", "backepsilon",
						"bepsi", "IOcy", "DJcy", "GJcy", "Jukcy", "DScy", "Iukcy", "YIcy", "Jsercy", "LJcy", "NJcy", "TSHcy",
						"KJcy", "Ubrcy", "DZcy", "Acy", "Bcy", "Vcy", "Gcy", "Dcy", "IEcy", "ZHcy", "Zcy", "Icy", "Jcy", "Kcy",
						"Lcy", "Mcy", "Ncy", "Ocy", "Pcy", "Rcy", "Scy", "Tcy", "Ucy", "Fcy", "KHcy", "TScy", "CHcy", "SHcy",
						"SHCHcy", "HARDcy", "Ycy", "SOFTcy", "Ecy", "YUcy", "YAcy", "acy", "bcy", "vcy", "gcy", "dcy", "iecy",
						"zhcy", "zcy", "icy", "jcy", "kcy", "lcy", "mcy", "ncy", "ocy", "pcy", "rcy", "scy", "tcy", "ucy", "fcy",
						"khcy", "tscy", "chcy", "shcy", "shchcy", "hardcy", "ycy", "softcy", "ecy", "yucy", "yacy", "iocy", "djcy",
						"gjcy", "jukcy", "dscy", "iukcy", "yicy", "jsercy", "ljcy", "njcy", "tshcy", "kjcy", "ubrcy", "dzcy",
						"ensp", "emsp", "emsp13", "emsp14", "numsp", "puncsp", "thinsp", "ThinSpace", "hairsp", "VeryThinSpace",
						"ZeroWidthSpace", "zwnj", "zwj", "lrm", "rlm", "dash", "hyphen", "ndash", "mdash", "horbar", "Verbar",
						"Vert", "lsquo", "OpenCurlyQuote", "CloseCurlyQuote", "rsquo", "rsquor", "lsquor", "sbquo", "ldquo",
						"OpenCurlyDoubleQuote", "CloseCurlyDoubleQuote", "rdquo", "rdquor", "bdquo", "ldquor", "dagger", "ddagger",
						"bull", "bullet", "nldr", "hellip", "mldr", "permil", "pertenk", "prime", "Prime", "tprime", "backprime",
						"bprime", "lsaquo", "rsaquo", "oline", "OverBar", "caret", "hybull", "frasl", "bsemi", "qprime",
						"MediumSpace", "NoBreak", "af", "ApplyFunction", "InvisibleTimes", "it", "ic", "InvisibleComma", "euro",
						"tdot", "TripleDot", "DotDot", "complexes", "Copf", "incare", "gscr", "hamilt", "HilbertSpace", "Hscr",
						"Hfr", "Poincareplane", "Hopf", "quaternions", "planckh", "hbar", "hslash", "planck", "plankv", "imagline",
						"Iscr", "Ifr", "Im", "image", "imagpart", "lagran", "Laplacetrf", "Lscr", "ell", "naturals", "Nopf",
						"numero", "copysr", "weierp", "wp", "Popf", "primes", "Qopf", "rationals", "realine", "Rscr", "Re", "real",
						"realpart", "Rfr", "reals", "Ropf", "rx", "TRADE", "trade", "integers", "Zopf", "mho", "zeetrf", "Zfr",
						"iiota", "bernou", "Bernoullis", "Bscr", "Cayleys", "Cfr", "escr", "Escr", "expectation", "Fouriertrf",
						"Fscr", "Mellintrf", "Mscr", "phmmat", "order", "orderof", "oscr", "alefsym", "aleph", "beth", "gimel",
						"daleth", "CapitalDifferentialD", "DD", "dd", "DifferentialD", "ee", "ExponentialE", "exponentiale", "ii",
						"ImaginaryI", "frac13", "frac23", "frac15", "frac25", "frac35", "frac45", "frac16", "frac56", "frac18",
						"frac38", "frac58", "frac78", "larr", "LeftArrow", "leftarrow", "ShortLeftArrow", "slarr", "ShortUpArrow",
						"uarr", "UpArrow", "uparrow", "rarr", "RightArrow", "rightarrow", "ShortRightArrow", "srarr", "darr",
						"DownArrow", "downarrow", "ShortDownArrow", "harr", "LeftRightArrow", "leftrightarrow", "UpDownArrow",
						"updownarrow", "varr", "nwarr", "nwarrow", "UpperLeftArrow", "nearr", "nearrow", "UpperRightArrow",
						"LowerRightArrow", "searr", "searrow", "LowerLeftArrow", "swarr", "swarrow", "nlarr", "nleftarrow",
						"nrarr", "nrightarrow", "rarrw", "rightsquigarrow", "Larr", "twoheadleftarrow", "Uarr", "Rarr",
						"twoheadrightarrow", "Darr", "larrtl", "leftarrowtail", "rarrtl", "rightarrowtail", "LeftTeeArrow",
						"mapstoleft", "mapstoup", "UpTeeArrow", "map", "mapsto", "RightTeeArrow", "DownTeeArrow", "mapstodown",
						"hookleftarrow", "larrhk", "hookrightarrow", "rarrhk", "larrlp", "looparrowleft", "looparrowright",
						"rarrlp", "harrw", "leftrightsquigarrow", "nharr", "nleftrightarrow", "Lsh", "lsh", "Rsh", "rsh", "ldsh",
						"rdsh", "crarr", "cularr", "curvearrowleft", "curarr", "curvearrowright", "circlearrowleft", "olarr",
						"circlearrowright", "orarr", "leftharpoonup", "LeftVector", "lharu", "DownLeftVector", "leftharpoondown",
						"lhard", "RightUpVector", "uharr", "upharpoonright", "LeftUpVector", "uharl", "upharpoonleft", "rharu",
						"rightharpoonup", "RightVector", "DownRightVector", "rhard", "rightharpoondown", "dharr",
						"downharpoonright", "RightDownVector", "dharl", "downharpoonleft", "LeftDownVector", "RightArrowLeftArrow",
						"rightleftarrows", "rlarr", "udarr", "UpArrowDownArrow", "LeftArrowRightArrow", "leftrightarrows", "lrarr",
						"leftleftarrows", "llarr", "upuparrows", "uuarr", "rightrightarrows", "rrarr", "ddarr", "downdownarrows",
						"leftrightharpoons", "lrhar", "ReverseEquilibrium", "Equilibrium", "rightleftharpoons", "rlhar", "nlArr",
						"nLeftarrow", "nhArr", "nLeftrightarrow", "nrArr", "nRightarrow", "DoubleLeftArrow", "lArr", "Leftarrow",
						"DoubleUpArrow", "uArr", "Uparrow", "DoubleRightArrow", "Implies", "rArr", "Rightarrow", "dArr",
						"DoubleDownArrow", "Downarrow", "DoubleLeftRightArrow", "hArr", "iff", "Leftrightarrow",
						"DoubleUpDownArrow", "Updownarrow", "vArr", "nwArr", "neArr", "seArr", "swArr", "lAarr", "Lleftarrow",
						"rAarr", "Rrightarrow", "zigrarr", "larrb", "LeftArrowBar", "rarrb", "RightArrowBar", "DownArrowUpArrow",
						"duarr", "loarr", "roarr", "hoarr", "ForAll", "forall", "comp", "complement", "part", "PartialD", "Exists",
						"exist", "nexist", "nexists", "NotExists", "empty", "emptyset", "emptyv", "varnothing", "Del", "nabla",
						"Element", "in", "isin", "isinv", "NotElement", "notin", "notinva", "ni", "niv", "ReverseElement",
						"SuchThat", "notni", "notniva", "NotReverseElement", "prod", "Product", "coprod", "Coproduct", "Sum",
						"sum", "minus", "MinusPlus", "mnplus", "mp", "dotplus", "plusdo", "Backslash", "setminus", "setmn",
						"smallsetminus", "ssetmn", "lowast", "compfn", "SmallCircle", "radic", "Sqrt", "prop", "Proportional",
						"propto", "varpropto", "vprop", "infin", "angrt", "ang", "angle", "angmsd", "measuredangle", "angsph",
						"mid", "shortmid", "smid", "VerticalBar", "nmid", "NotVerticalBar", "nshortmid", "nsmid",
						"DoubleVerticalBar", "par", "parallel", "shortparallel", "spar", "NotDoubleVerticalBar", "npar",
						"nparallel", "nshortparallel", "nspar", "and", "wedge", "or", "vee", "cap", "cup", "int", "Integral",
						"Int", "iiint", "tint", "conint", "ContourIntegral", "oint", "Conint", "DoubleContourIntegral", "Cconint",
						"cwint", "cwconint", "ClockwiseContourIntegral", "cwconint", "awconint", "there4", "Therefore",
						"therefore", "because", "ratio", "Colon", "Proportion", "dotminus", "minusd", "mDDot", "homtht", "sim",
						"thicksim", "thksim", "Tilde", "backsim", "bsim", "ac", "mstpos", "acd", "VerticalTilde", "wr", "wreath",
						"NotTilde", "nsim", "eqsim", "EqualTilde", "esim", "sime", "simeq", "TildeEqual", "NotTildeEqual", "nsime",
						"nsimeq", "cong", "TildeFullEqual", "simne", "ncong", "NotTildeFullEqual", "ap", "approx", "asymp",
						"thickapprox", "thkap", "TildeTilde", "nap", "napprox", "NotTildeTilde", "ape", "approxeq", "apid",
						"backcong", "bcong", "asympeq", "CupCap", "bump", "Bumpeq", "HumpDownHump", "bumpe", "bumpeq", "HumpEqual",
						"doteq", "DotEqual", "esdot", "doteqdot", "eDot", "efDot", "fallingdotseq", "erDot", "risingdotseq",
						"Assign", "colone", "coloneq", "ecolon", "eqcolon", "ecir", "eqcirc", "circeq", "cire", "wedgeq", "veeeq",
						"triangleq", "trie", "equest", "questeq", "ne", "NotEqual", "Congruent", "equiv", "nequiv", "NotCongruent",
						"le", "leq", "ge", "geq", "GreaterEqual", "lE", "leqq", "LessFullEqual", "gE", "geqq", "GreaterFullEqual",
						"lnE", "lneqq", "gnE", "gneqq", "ll", "Lt", "NestedLessLess", "gg", "Gt", "NestedGreaterGreater",
						"between", "twixt", "NotCupCap", "nless", "nlt", "NotLess", "ngt", "ngtr", "NotGreater", "nle", "nleq",
						"NotLessEqual", "nge", "ngeq", "NotGreaterEqual", "lesssim", "LessTilde", "lsim", "GreaterTilde", "gsim",
						"gtrsim", "nlsim", "NotLessTilde", "ngsim", "NotGreaterTilde", "LessGreater", "lessgtr", "lg", "gl",
						"GreaterLess", "gtrless", "NotLessGreater", "ntlg", "NotGreaterLess", "ntgl", "pr", "prec", "Precedes",
						"sc", "succ", "Succeeds", "prcue", "preccurlyeq", "PrecedesSlantEqual", "sccue", "succcurlyeq",
						"SucceedsSlantEqual", "PrecedesTilde", "precsim", "prsim", "scsim", "SucceedsTilde", "succsim",
						"NotPrecedes", "npr", "nprec", "NotSucceeds", "nsc", "nsucc", "sub", "subset", "sup", "Superset", "supset",
						"nsub", "nsup", "sube", "subseteq", "SubsetEqual", "supe", "SupersetEqual", "supseteq", "NotSubsetEqual",
						"nsube", "nsubseteq", "NotSupersetEqual", "nsupe", "nsupseteq", "subne", "subsetneq", "supne", "supsetneq",
						"cupdot", "UnionPlus", "uplus", "sqsub", "sqsubset", "SquareSubset", "sqsup", "sqsupset", "SquareSuperset",
						"sqsube", "sqsubseteq", "SquareSubsetEqual", "sqsupe", "sqsupseteq", "SquareSupersetEqual", "sqcap",
						"SquareIntersection", "sqcup", "SquareUnion", "CirclePlus", "oplus", "CircleMinus", "ominus",
						"CircleTimes", "otimes", "osol", "CircleDot", "odot", "circledcirc", "ocir", "circledast", "oast",
						"circleddash", "odash", "boxplus", "plusb", "boxminus", "minusb", "boxtimes", "timesb", "dotsquare",
						"sdotb", "RightTee;", "vdash", "dashv", "LeftTee", "DownTee", "top", "bot", "bottom", "perp", "UpTee",
						"models", "DoubleRightTee", "vDash", "Vdash", "Vvdash", "VDash", "nvdash", "nvDash", "nVdash", "nVDash",
						"prurel", "LeftTriangle", "vartriangleleft", "vltri", "RightTriangle", "vartriangleright", "vrtri",
						"LeftTriangleEqual", "ltrie", "trianglelefteq", "RightTriangleEqual", "rtrie", "trianglerighteq", "origof",
						"imof", "multimap", "mumap", "hercon", "intcal", "intercal", "veebar", "barvee", "angrtvb", "lrtri",
						"bigwedge", "Wedge", "xwedge", "bigvee", "Vee", "xvee", "bigcap", "Intersection", "xcap", "bigcup",
						"Union", "xcup", "diam", "Diamond", "diamond", "sdot", "sstarf", "Star", "divideontimes", "divonx",
						"bowtie", "ltimes", "rtimes", "leftthreetimes", "lthree", "rightthreetimes", "rthree", "backsimeq",
						"bsime", "curlyvee", "cuvee", "curlywedge", "cuwed", "Sub", "Subset", "Sup", "Supset", "Cap", "Cup",
						"fork", "pitchfork", "epar", "lessdot", "ltdot", "gtdot", "gtrdot", "Ll", "Gg", "ggg", "leg", "lesseqgtr",
						"LessEqualGreater", "gel", "GreaterEqualLess", "gtreqless", "cuepr", "curlyeqprec", "cuesc", "curlyeqsucc",
						"NotPrecedesSlantEqual", "nprcue", "NotSucceedsSlantEqual", "nsccue", "NotSquareSubsetEqual", "nsqsube",
						"NotSquareSupersetEqual", "nsqsupe", "lnsim", "gnsim", "precnsim", "prnsim", "scnsim", "succnsim", "nltri",
						"NotLeftTriangle", "ntriangleleft", "NotRightTriangle", "nrtri", "ntriangleright", "nltrie",
						"NotLeftTriangleEqual", "ntrianglelefteq", "NotRightTriangleEqual", "nrtrie", "ntrianglerighteq", "vellip",
						"ctdot", "utdot", "dtdot", "disin", "isinsv", "isins", "isindot", "notinvc", "notinvb", "isinE", "nisd",
						"xnis", "nis", "notnivc", "notnivb", "barwedge", "doublebarwedge", "lceil", "LeftCeiling", "rceil",
						"RightCeiling", "LeftFloor", "lfloor", "rfloor", "RightFloor", "drcrop", "dlcrop", "urcrop", "ulcrop",
						"bnot", "profline", "profsurf", "telrec", "target", "ulcorn", "ulcorner", "urcorn", "urcorner", "dlcorn",
						"llcorner", "drcorn", "lrcorner", "frown", "sfrown", "smile", "ssmile", "cylcty", "profalar", "topbot",
						"ovbar", "solbar", "angzarr", "lmoust", "lmoustache", "rmoust", "rmoustache", "OverBracket", "tbrk",
						"bbrk", "UnderBracket", "bbrktbrk", "OverParenthesis", "UnderParenthesis", "OverBrace", "UnderBrace",
						"trpezium", "elinters", "blank", "circledS", "oS", "boxh", "HorizontalLine", "boxv", "boxdr", "boxdl",
						"boxur", "boxul", "boxvr", "boxvl", "boxhd", "boxhu", "boxvh", "boxH", "boxV", "boxdR", "boxDr", "boxDR",
						"boxdL", "boxDl", "boxDL", "boxuR", "boxUr", "boxUR", "boxuL", "boxUl", "boxUL", "boxvR", "boxVr", "boxVR",
						"boxvL", "boxVl", "boxVL", "boxHd", "boxhD", "boxHD", "boxHu", "boxhU", "boxHU", "boxvH", "boxVh", "boxVH",
						"uhblk", "lhblk", "block", "blk14", "blk12", "blk34", "squ", "Square", "square", "blacksquare",
						"FilledVerySmallSquare", "squarf", "squf", "EmptyVerySmallSquare", "rect", "marker", "fltns",
						"bigtriangleup", "xutri", "blacktriangle", "utrif", "triangle", "utri", "blacktriangleright", "rtrif",
						"rtri", "triangleright", "bigtriangledown", "xdtri", "blacktriangledown", "dtrif", "dtri", "triangledown",
						"blacktriangleleft", "ltrif", "ltri", "triangleleft", "loz", "lozenge", "cir", "tridot", "bigcirc",
						"xcirc", "ultri", "urtri", "lltri", "EmptySmallSquare", "FilledSmallSquare", "bigstar", "starf", "star",
						"phone", "female", "male", "spades", "spadesuit", "clubs", "clubsuit", "hearts", "heartsuit",
						"diamondsuit", "diams", "sung", "flat", "natur", "natural", "sharp", "check", "checkmark", "cross", "malt",
						"maltese", "sext", "VerticalSeparator", "lbbrk", "rbbrk", "bsolhsub", "suphsol", "LeftDoubleBracket",
						"lobrk", "RightDoubleBracket", "robrk", "lang", "langle", "LeftAngleBracket", "rang", "rangle",
						"RightAngleBracket", "Lang", "Rang", "loang", "roang", "LongLeftArrow", "longleftarrow", "xlarr",
						"LongRightArrow", "longrightarrow", "xrarr", "LongLeftRightArrow", "longleftrightarrow", "xharr",
						"DoubleLongLeftArrow", "Longleftarrow", "xlArr", "DoubleLongRightArrow", "Longrightarrow", "xrArr",
						"DoubleLongLeftRightArrow", "Longleftrightarrow", "xhArr", "longmapsto", "xmap", "dzigrarr", "nvlArr",
						"nvrArr", "nvHarr", "Map", "lbarr", "bkarow", "rbarr", "lBarr", "dbkarow", "rBarr", "drbkarow", "RBarr",
						"DDotrahd", "UpArrowBar", "DownArrowBar", "Rarrtl", "latail", "ratail", "lAtail", "rAtail", "larrfs",
						"rarrfs", "larrbfs", "rarrbfs", "nwarhk", "nearhk", "hksearow", "searhk", "hkswarow", "swarhk", "nwnear",
						"nesear", "toea", "seswar", "tosa", "swnwar", "rarrc", "cudarrr", "ldca", "rdca", "cudarrl", "larrpl",
						"curarrm", "cularrp", "rarrpl", "harrcir", "Uarrocir", "lurdshar", "ldrushar", "LeftRightVector",
						"RightUpDownVector", "DownLeftRightVector", "LeftUpDownVector", "LeftVectorBar", "RightVectorBar",
						"RightUpVectorBar", "RightDownVectorBar", "DownLeftVectorBar", "DownRightVectorBar", "LeftUpVectorBar",
						"LeftDownVectorBar", "LeftTeeVector", "RightTeeVector", "RightUpTeeVector", "RightDownTeeVector",
						"DownLeftTeeVector", "DownRightTeeVector", "LeftUpTeeVector", "LeftDownTeeVector", "lHar", "uHar", "rHar",
						"dHar", "luruhar", "ldrdhar", "ruluhar", "rdldhar", "lharul", "llhard", "rharul", "lrhard", "udhar",
						"UpEquilibrium", "duhar", "ReverseUpEquilibrium", "RoundImplies", "erarr", "simrarr", "larrsim", "rarrsim",
						"rarrap", "ltlarr", "gtrarr", "subrarr", "suplarr", "lfisht", "rfisht", "ufisht", "dfisht", "lopar",
						"ropar", "lbrke", "rbrke", "lbrkslu", "rbrksld", "lbrksld", "rbrkslu", "langd", "rangd", "lparlt",
						"rpargt", "gtlPar", "ltrPar", "vzigzag", "vangrt", "angrtvbd", "ange", "range", "dwangle", "uwangle",
						"angmsdaa", "angmsdab", "angmsdac", "angmsdad", "angmsdae", "angmsdaf", "angmsdag", "angmsdah", "bemptyv",
						"demptyv", "cemptyv", "raemptyv", "laemptyv", "ohbar", "omid", "opar", "operp", "olcross", "odsold",
						"olcir", "ofcir", "olt", "ogt", "cirscir", "cirE", "solb", "bsolb", "boxbox", "trisb", "rtriltri",
						"LeftTriangleBar", "RightTriangleBar", "iinfin", "infintie", "nvinfin", "eparsl", "smeparsl", "eqvparsl",
						"blacklozenge", "lozf", "RuleDelayed", "dsol", "bigodot", "xodot", "bigoplus", "xoplus", "bigotimes",
						"xotime", "biguplus", "xuplus", "bigsqcup", "xsqcup", "iiiint", "qint", "fpartint", "cirfnint", "awint",
						"rppolint", "scpolint", "npolint", "pointint", "quatint", "intlarhk", "pluscir", "plusacir", "simplus",
						"plusdu", "plussim", "plustwo", "mcomma", "minusdu", "loplus", "roplus", "Cross", "timesd", "timesbar",
						"smashp", "lotimes", "rotimes", "otimesas", "Otimes", "odiv", "triplus", "triminus", "tritime", "intprod",
						"iprod", "amalg", "capdot", "ncup", "ncap", "capand", "cupor", "cupcap", "capcup", "cupbrcap", "capbrcup",
						"cupcup", "capcap", "ccups", "ccaps", "ccupssm", "And", "Or", "andand", "oror", "orslope", "andslope",
						"andv", "orv", "andd", "ord", "wedbar", "sdote", "simdot", "congdot", "easter", "apacir", "apE", "eplus",
						"pluse", "Esim", "Colone", "Equal", "ddotseq", "eDDot", "equivDD", "ltcir", "gtcir", "ltquest", "gtquest",
						"leqslant", "les", "LessSlantEqual", "geqslant", "ges", "GreaterSlantEqual", "lesdot", "gesdot", "lesdoto",
						"gesdoto", "lesdotor", "gesdotol", "lap", "lessapprox", "gap", "gtrapprox", "lne", "lneq", "gne", "gneq",
						"lnap", "lnapprox", "gnap", "gnapprox", "lEg", "lesseqqgtr", "gEl", "gtreqqless", "lsime", "gsime",
						"lsimg", "gsiml", "lgE", "glE", "lesges", "gesles", "els", "eqslantless", "egs", "eqslantgtr", "elsdot",
						"egsdot", "el", "eg", "siml", "simg", "simlE", "simgE", "LessLess", "GreaterGreater", "glj", "gla", "ltcc",
						"gtcc", "lescc", "gescc", "smt", "lat", "smte", "late", "bumpE", "pre", "PrecedesEqual", "preceq", "sce",
						"SucceedsEqual", "succeq", "prE", "scE", "precneqq", "prnE", "scnE", "succneqq", "prap", "precapprox",
						"scap", "succapprox", "precnapprox", "prnap", "scnap", "succnapprox", "Pr", "Sc", "subdot", "supdot",
						"subplus", "supplus", "submult", "supmult", "subedot", "supedot", "subE", "subseteqq", "supE", "supseteqq",
						"subsim", "supsim", "subnE", "subsetneqq", "supnE", "supsetneqq", "csub", "csup", "csube", "csupe",
						"subsup", "supsub", "subsub", "supsup", "suphsub", "supdsub", "forkv", "topfork", "mlcp", "Dashv",
						"DoubleLeftTee", "Vdashl", "Barv", "vBar", "vBarv", "Vbar", "Not", "bNot", "rnmid", "cirmid", "midcir",
						"topcir", "nhpar", "parsim", "parsl", "fflig", "filig", "fllig", "ffilig", "ffllig", "Ascr", "Cscr",
						"Dscr", "Gscr", "Jscr", "Kscr", "Nscr", "Oscr", "Pscr", "Qscr", "Sscr", "Tscr", "Uscr", "Vscr", "Wscr",
						"Xscr", "Yscr", "Zscr", "ascr", "bscr", "cscr", "dscr", "fscr", "hscr", "iscr", "jscr", "kscr", "lscr",
						"mscr", "nscr", "pscr", "qscr", "rscr", "sscr", "tscr", "uscr", "vscr", "wscr", "xscr", "yscr", "zscr",
						"Afr", "Bfr", "Dfr", "Efr", "Ffr", "Gfr", "Jfr", "Kfr", "Lfr", "Mfr", "Nfr", "Ofr", "Pfr", "Qfr", "Sfr",
						"Tfr", "Ufr", "Vfr", "Wfr", "Xfr", "Yfr", "afr", "bfr", "cfr", "dfr", "efr", "ffr", "gfr", "hfr", "ifr",
						"jfr", "kfr", "lfr", "mfr", "nfr", "ofr", "pfr", "qfr", "rfr", "sfr", "tfr", "ufr", "vfr", "wfr", "xfr",
						"yfr", "zfr", "Aopf", "Bopf", "Dopf", "Eopf", "Fopf", "Gopf", "Iopf", "Jopf", "Kopf", "Lopf", "Mopf",
						"Oopf", "Sopf", "Topf", "Uopf", "Vopf", "Wopf", "Xopf", "Yopf", "aopf", "bopf", "copf", "dopf", "eopf",
						"fopf", "gopf", "hopf", "iopf", "jopf", "kopf", "lopf", "mopf", "nopf", "oopf", "popf", "qopf", "ropf",
						"sopf", "topf", "uopf", "vopf", "wopf", "xopf", "yopf", "zopf", "nvlt", "bne", "nvgt", "fjlig",
						"ThickSpace", "nrarrw", "npart", "nang", "caps", "cups", "nvsim", "race", "acE", "nesim", "NotEqualTilde",
						"napid", "nvap", "nbump", "NotHumpDownHump", "nbumpe", "NotHumpEqual", "nedot", "bnequiv", "nvle", "nvge",
						"nlE", "nleqq", "ngE", "ngeqq", "NotGreaterFullEqual", "lvertneqq", "lvnE", "gvertneqq", "gvnE", "nLtv",
						"NotLessLess", "nLt", "nGtv", "NotGreaterGreater", "nGt", "NotSucceedsTilde", "NotSubset", "nsubset",
						"vnsub", "NotSuperset", "nsupset", "vnsup", "varsubsetneq", "vsubne", "varsupsetneq", "vsupne",
						"NotSquareSubset", "NotSquareSuperset", "sqcaps", "sqcups", "nvltrie", "nvrtrie", "nLl", "nGg", "lesg",
						"gesl", "notindot", "notinE", "nrarrc", "NotLeftTriangleBar", "NotRightTriangleBar", "ncongdot", "napE",
						"nleqslant", "nles", "NotLessSlantEqual", "ngeqslant", "nges", "NotGreaterSlantEqual", "NotNestedLessLess",
						"NotNestedGreaterGreater", "smtes", "lates", "NotPrecedesEqual", "npre", "npreceq", "NotSucceedsEqual",
						"nsce", "nsucceq", "nsubE", "nsubseteqq", "nsupE", "nsupseteqq", "varsubsetneqq", "vsubnE",
						"varsupsetneqq", "vsupnE", "nparsl"};
		private static final int[] CODEPOINTS =
				{33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 42, 43, 44, 46, 47, 58, 59, 60, 61, 62, 63, 64, 91, 91, 92, 93, 93,
						94, 95, 95, 96, 96, 123, 123, 124, 124, 124, 125, 125, 160, 160, 161, 162, 163, 164, 165, 166, 167, 168,
						168, 168, 168, 169, 170, 171, 172, 173, 174, 174, 175, 175, 176, 177, 177, 178, 179, 180, 180, 181, 182,
						183, 183, 183, 184, 184, 185, 186, 187, 188, 189, 189, 190, 191, 192, 193, 194, 195, 196, 197, 197, 198,
						199, 200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 217, 218, 219,
						220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 240,
						241, 242, 243, 244, 245, 246, 247, 247, 248, 249, 250, 251, 252, 253, 254, 255, 256, 257, 258, 259, 260,
						261, 262, 263, 264, 265, 266, 267, 268, 269, 270, 271, 272, 273, 274, 275, 278, 279, 280, 281, 282, 283,
						284, 285, 286, 287, 288, 289, 290, 292, 293, 294, 295, 296, 297, 298, 299, 302, 303, 304, 305, 305, 306,
						307, 308, 309, 310, 311, 312, 313, 314, 315, 316, 317, 318, 319, 320, 321, 322, 323, 324, 325, 326, 327,
						328, 329, 330, 331, 332, 333, 336, 337, 338, 339, 340, 341, 342, 343, 344, 345, 346, 347, 348, 349, 350,
						351, 352, 353, 354, 355, 356, 357, 358, 359, 360, 361, 362, 363, 364, 365, 366, 367, 368, 369, 370, 371,
						372, 373, 374, 375, 376, 377, 378, 379, 380, 381, 382, 402, 437, 501, 567, 710, 711, 711, 728, 728, 729,
						729, 730, 731, 732, 732, 733, 733, 785, 913, 914, 915, 916, 917, 918, 919, 920, 921, 922, 923, 924, 925,
						926, 927, 928, 929, 931, 932, 933, 934, 935, 936, 937, 937, 945, 946, 947, 948, 949, 949, 950, 951, 952,
						953, 954, 955, 956, 957, 958, 959, 960, 961, 962, 962, 962, 963, 964, 965, 965, 966, 967, 968, 969, 977,
						977, 977, 978, 978, 981, 981, 981, 982, 982, 988, 989, 989, 1008, 1008, 1009, 1009, 1013, 1013, 1013, 1014,
						1014, 1025, 1026, 1027, 1028, 1029, 1030, 1031, 1032, 1033, 1034, 1035, 1036, 1038, 1039, 1040, 1041, 1042,
						1043, 1044, 1045, 1046, 1047, 1048, 1049, 1050, 1051, 1052, 1053, 1054, 1055, 1056, 1057, 1058, 1059, 1060,
						1061, 1062, 1063, 1064, 1065, 1066, 1067, 1068, 1069, 1070, 1071, 1072, 1073, 1074, 1075, 1076, 1077, 1078,
						1079, 1080, 1081, 1082, 1083, 1084, 1085, 1086, 1087, 1088, 1089, 1090, 1091, 1092, 1093, 1094, 1095, 1096,
						1097, 1098, 1099, 1100, 1101, 1102, 1103, 1105, 1106, 1107, 1108, 1109, 1110, 1111, 1112, 1113, 1114, 1115,
						1116, 1118, 1119, 8194, 8195, 8196, 8197, 8199, 8200, 8201, 8201, 8202, 8202, 8203, 8204, 8205, 8206, 8207,
						8208, 8208, 8211, 8212, 8213, 8214, 8214, 8216, 8216, 8217, 8217, 8217, 8218, 8218, 8220, 8220, 8221, 8221,
						8221, 8222, 8222, 8224, 8225, 8226, 8226, 8229, 8230, 8230, 8240, 8241, 8242, 8243, 8244, 8245, 8245, 8249,
						8250, 8254, 8254, 8257, 8259, 8260, 8271, 8279, 8287, 8288, 8289, 8289, 8290, 8290, 8291, 8291, 8364, 8411,
						8411, 8412, 8450, 8450, 8453, 8458, 8459, 8459, 8459, 8460, 8460, 8461, 8461, 8462, 8463, 8463, 8463, 8463,
						8464, 8464, 8465, 8465, 8465, 8465, 8466, 8466, 8466, 8467, 8469, 8469, 8470, 8471, 8472, 8472, 8473, 8473,
						8474, 8474, 8475, 8475, 8476, 8476, 8476, 8476, 8477, 8477, 8478, 8482, 8482, 8484, 8484, 8487, 8488, 8488,
						8489, 8492, 8492, 8492, 8493, 8493, 8495, 8496, 8496, 8497, 8497, 8499, 8499, 8499, 8500, 8500, 8500, 8501,
						8501, 8502, 8503, 8504, 8517, 8517, 8518, 8518, 8519, 8519, 8519, 8520, 8520, 8531, 8532, 8533, 8534, 8535,
						8536, 8537, 8538, 8539, 8540, 8541, 8542, 8592, 8592, 8592, 8592, 8592, 8593, 8593, 8593, 8593, 8594, 8594,
						8594, 8594, 8594, 8595, 8595, 8595, 8595, 8596, 8596, 8596, 8597, 8597, 8597, 8598, 8598, 8598, 8599, 8599,
						8599, 8600, 8600, 8600, 8601, 8601, 8601, 8602, 8602, 8603, 8603, 8605, 8605, 8606, 8606, 8607, 8608, 8608,
						8609, 8610, 8610, 8611, 8611, 8612, 8612, 8613, 8613, 8614, 8614, 8614, 8615, 8615, 8617, 8617, 8618, 8618,
						8619, 8619, 8620, 8620, 8621, 8621, 8622, 8622, 8624, 8624, 8625, 8625, 8626, 8627, 8629, 8630, 8630, 8631,
						8631, 8634, 8634, 8635, 8635, 8636, 8636, 8636, 8637, 8637, 8637, 8638, 8638, 8638, 8639, 8639, 8639, 8640,
						8640, 8640, 8641, 8641, 8641, 8642, 8642, 8642, 8643, 8643, 8643, 8644, 8644, 8644, 8645, 8645, 8646, 8646,
						8646, 8647, 8647, 8648, 8648, 8649, 8649, 8650, 8650, 8651, 8651, 8651, 8652, 8652, 8652, 8653, 8653, 8654,
						8654, 8655, 8655, 8656, 8656, 8656, 8657, 8657, 8657, 8658, 8658, 8658, 8658, 8659, 8659, 8659, 8660, 8660,
						8660, 8660, 8661, 8661, 8661, 8662, 8663, 8664, 8665, 8666, 8666, 8667, 8667, 8669, 8676, 8676, 8677, 8677,
						8693, 8693, 8701, 8702, 8703, 8704, 8704, 8705, 8705, 8706, 8706, 8707, 8707, 8708, 8708, 8708, 8709, 8709,
						8709, 8709, 8711, 8711, 8712, 8712, 8712, 8712, 8713, 8713, 8713, 8715, 8715, 8715, 8715, 8716, 8716, 8716,
						8719, 8719, 8720, 8720, 8721, 8721, 8722, 8723, 8723, 8723, 8724, 8724, 8726, 8726, 8726, 8726, 8726, 8727,
						8728, 8728, 8730, 8730, 8733, 8733, 8733, 8733, 8733, 8734, 8735, 8736, 8736, 8737, 8737, 8738, 8739, 8739,
						8739, 8739, 8740, 8740, 8740, 8740, 8741, 8741, 8741, 8741, 8741, 8742, 8742, 8742, 8742, 8742, 8743, 8743,
						8744, 8744, 8745, 8746, 8747, 8747, 8748, 8749, 8749, 8750, 8750, 8750, 8751, 8751, 8752, 8753, 8754, 8754,
						8754, 8755, 8756, 8756, 8756, 8757, 8758, 8759, 8759, 8760, 8760, 8762, 8763, 8764, 8764, 8764, 8764, 8765,
						8765, 8766, 8766, 8767, 8768, 8768, 8768, 8769, 8769, 8770, 8770, 8770, 8771, 8771, 8771, 8772, 8772, 8772,
						8773, 8773, 8774, 8775, 8775, 8776, 8776, 8776, 8776, 8776, 8776, 8777, 8777, 8777, 8778, 8778, 8779, 8780,
						8780, 8781, 8781, 8782, 8782, 8782, 8783, 8783, 8783, 8784, 8784, 8784, 8785, 8785, 8786, 8786, 8787, 8787,
						8788, 8788, 8788, 8789, 8789, 8790, 8790, 8791, 8791, 8793, 8794, 8796, 8796, 8799, 8799, 8800, 8800, 8801,
						8801, 8802, 8802, 8804, 8804, 8805, 8805, 8805, 8806, 8806, 8806, 8807, 8807, 8807, 8808, 8808, 8809, 8809,
						8810, 8810, 8810, 8811, 8811, 8811, 8812, 8812, 8813, 8814, 8814, 8814, 8815, 8815, 8815, 8816, 8816, 8816,
						8817, 8817, 8817, 8818, 8818, 8818, 8819, 8819, 8819, 8820, 8820, 8821, 8821, 8822, 8822, 8822, 8823, 8823,
						8823, 8824, 8824, 8825, 8825, 8826, 8826, 8826, 8827, 8827, 8827, 8828, 8828, 8828, 8829, 8829, 8829, 8830,
						8830, 8830, 8831, 8831, 8831, 8832, 8832, 8832, 8833, 8833, 8833, 8834, 8834, 8835, 8835, 8835, 8836, 8837,
						8838, 8838, 8838, 8839, 8839, 8839, 8840, 8840, 8840, 8841, 8841, 8841, 8842, 8842, 8843, 8843, 8845, 8846,
						8846, 8847, 8847, 8847, 8848, 8848, 8848, 8849, 8849, 8849, 8850, 8850, 8850, 8851, 8851, 8852, 8852, 8853,
						8853, 8854, 8854, 8855, 8855, 8856, 8857, 8857, 8858, 8858, 8859, 8859, 8861, 8861, 8862, 8862, 8863, 8863,
						8864, 8864, 8865, 8865, 8866, 8866, 8867, 8867, 8868, 8868, 8869, 8869, 8869, 8869, 8871, 8872, 8872, 8873,
						8874, 8875, 8876, 8877, 8878, 8879, 8880, 8882, 8882, 8882, 8883, 8883, 8883, 8884, 8884, 8884, 8885, 8885,
						8885, 8886, 8887, 8888, 8888, 8889, 8890, 8890, 8891, 8893, 8894, 8895, 8896, 8896, 8896, 8897, 8897, 8897,
						8898, 8898, 8898, 8899, 8899, 8899, 8900, 8900, 8900, 8901, 8902, 8902, 8903, 8903, 8904, 8905, 8906, 8907,
						8907, 8908, 8908, 8909, 8909, 8910, 8910, 8911, 8911, 8912, 8912, 8913, 8913, 8914, 8915, 8916, 8916, 8917,
						8918, 8918, 8919, 8919, 8920, 8921, 8921, 8922, 8922, 8922, 8923, 8923, 8923, 8926, 8926, 8927, 8927, 8928,
						8928, 8929, 8929, 8930, 8930, 8931, 8931, 8934, 8935, 8936, 8936, 8937, 8937, 8938, 8938, 8938, 8939, 8939,
						8939, 8940, 8940, 8940, 8941, 8941, 8941, 8942, 8943, 8944, 8945, 8946, 8947, 8948, 8949, 8950, 8951, 8953,
						8954, 8955, 8956, 8957, 8958, 8965, 8966, 8968, 8968, 8969, 8969, 8970, 8970, 8971, 8971, 8972, 8973, 8974,
						8975, 8976, 8978, 8979, 8981, 8982, 8988, 8988, 8989, 8989, 8990, 8990, 8991, 8991, 8994, 8994, 8995, 8995,
						9005, 9006, 9014, 9021, 9023, 9084, 9136, 9136, 9137, 9137, 9140, 9140, 9141, 9141, 9142, 9180, 9181, 9182,
						9183, 9186, 9191, 9251, 9416, 9416, 9472, 9472, 9474, 9484, 9488, 9492, 9496, 9500, 9508, 9516, 9524, 9532,
						9552, 9553, 9554, 9555, 9556, 9557, 9558, 9559, 9560, 9561, 9562, 9563, 9564, 9565, 9566, 9567, 9568, 9569,
						9570, 9571, 9572, 9573, 9574, 9575, 9576, 9577, 9578, 9579, 9580, 9600, 9604, 9608, 9617, 9618, 9619, 9633,
						9633, 9633, 9642, 9642, 9642, 9642, 9643, 9645, 9646, 9649, 9651, 9651, 9652, 9652, 9653, 9653, 9656, 9656,
						9657, 9657, 9661, 9661, 9662, 9662, 9663, 9663, 9666, 9666, 9667, 9667, 9674, 9674, 9675, 9708, 9711, 9711,
						9720, 9721, 9722, 9723, 9724, 9733, 9733, 9734, 9742, 9792, 9794, 9824, 9824, 9827, 9827, 9829, 9829, 9830,
						9830, 9834, 9837, 9838, 9838, 9839, 10003, 10003, 10007, 10016, 10016, 10038, 10072, 10098, 10099, 10184,
						10185, 10214, 10214, 10215, 10215, 10216, 10216, 10216, 10217, 10217, 10217, 10218, 10219, 10220, 10221,
						10229, 10229, 10229, 10230, 10230, 10230, 10231, 10231, 10231, 10232, 10232, 10232, 10233, 10233, 10233,
						10234, 10234, 10234, 10236, 10236, 10239, 10498, 10499, 10500, 10501, 10508, 10509, 10509, 10510, 10511,
						10511, 10512, 10512, 10513, 10514, 10515, 10518, 10521, 10522, 10523, 10524, 10525, 10526, 10527, 10528,
						10531, 10532, 10533, 10533, 10534, 10534, 10535, 10536, 10536, 10537, 10537, 10538, 10547, 10549, 10550,
						10551, 10552, 10553, 10556, 10557, 10565, 10568, 10569, 10570, 10571, 10574, 10575, 10576, 10577, 10578,
						10579, 10580, 10581, 10582, 10583, 10584, 10585, 10586, 10587, 10588, 10589, 10590, 10591, 10592, 10593,
						10594, 10595, 10596, 10597, 10598, 10599, 10600, 10601, 10602, 10603, 10604, 10605, 10606, 10606, 10607,
						10607, 10608, 10609, 10610, 10611, 10612, 10613, 10614, 10616, 10617, 10619, 10620, 10621, 10622, 10623,
						10629, 10630, 10635, 10636, 10637, 10638, 10639, 10640, 10641, 10642, 10643, 10644, 10645, 10646, 10650,
						10652, 10653, 10660, 10661, 10662, 10663, 10664, 10665, 10666, 10667, 10668, 10669, 10670, 10671, 10672,
						10673, 10674, 10675, 10676, 10677, 10678, 10679, 10681, 10683, 10684, 10686, 10687, 10688, 10689, 10690,
						10691, 10692, 10693, 10697, 10701, 10702, 10703, 10704, 10716, 10717, 10718, 10723, 10724, 10725, 10731,
						10731, 10740, 10742, 10752, 10752, 10753, 10753, 10754, 10754, 10756, 10756, 10758, 10758, 10764, 10764,
						10765, 10768, 10769, 10770, 10771, 10772, 10773, 10774, 10775, 10786, 10787, 10788, 10789, 10790, 10791,
						10793, 10794, 10797, 10798, 10799, 10800, 10801, 10803, 10804, 10805, 10806, 10807, 10808, 10809, 10810,
						10811, 10812, 10812, 10815, 10816, 10818, 10819, 10820, 10821, 10822, 10823, 10824, 10825, 10826, 10827,
						10828, 10829, 10832, 10835, 10836, 10837, 10838, 10839, 10840, 10842, 10843, 10844, 10845, 10847, 10854,
						10858, 10861, 10862, 10863, 10864, 10865, 10866, 10867, 10868, 10869, 10871, 10871, 10872, 10873, 10874,
						10875, 10876, 10877, 10877, 10877, 10878, 10878, 10878, 10879, 10880, 10881, 10882, 10883, 10884, 10885,
						10885, 10886, 10886, 10887, 10887, 10888, 10888, 10889, 10889, 10890, 10890, 10891, 10891, 10892, 10892,
						10893, 10894, 10895, 10896, 10897, 10898, 10899, 10900, 10901, 10901, 10902, 10902, 10903, 10904, 10905,
						10906, 10909, 10910, 10911, 10912, 10913, 10914, 10916, 10917, 10918, 10919, 10920, 10921, 10922, 10923,
						10924, 10925, 10926, 10927, 10927, 10927, 10928, 10928, 10928, 10931, 10932, 10933, 10933, 10934, 10934,
						10935, 10935, 10936, 10936, 10937, 10937, 10938, 10938, 10939, 10940, 10941, 10942, 10943, 10944, 10945,
						10946, 10947, 10948, 10949, 10949, 10950, 10950, 10951, 10952, 10955, 10955, 10956, 10956, 10959, 10960,
						10961, 10962, 10963, 10964, 10965, 10966, 10967, 10968, 10969, 10970, 10971, 10980, 10980, 10982, 10983,
						10984, 10985, 10987, 10988, 10989, 10990, 10991, 10992, 10993, 10994, 10995, 11005, 64256, 64257, 64258,
						64259, 64260, 119964, 119966, 119967, 119970, 119973, 119974, 119977, 119978, 119979, 119980, 119982,
						119983, 119984, 119985, 119986, 119987, 119988, 119989, 119990, 119991, 119992, 119993, 119995, 119997,
						119998, 119999, 120000, 120001, 120002, 120003, 120005, 120006, 120007, 120008, 120009, 120010, 120011,
						120012, 120013, 120014, 120015, 120068, 120069, 120071, 120072, 120073, 120074, 120077, 120078, 120079,
						120080, 120081, 120082, 120083, 120084, 120086, 120087, 120088, 120089, 120090, 120091, 120092, 120094,
						120095, 120096, 120097, 120098, 120099, 120100, 120101, 120102, 120103, 120104, 120105, 120106, 120107,
						120108, 120109, 120110, 120111, 120112, 120113, 120114, 120115, 120116, 120117, 120118, 120119, 120120,
						120121, 120123, 120124, 120125, 120126, 120128, 120129, 120130, 120131, 120132, 120134, 120138, 120139,
						120140, 120141, 120142, 120143, 120144, 120146, 120147, 120148, 120149, 120150, 120151, 120152, 120153,
						120154, 120155, 120156, 120157, 120158, 120159, 120160, 120161, 120162, 120163, 120164, 120165, 120166,
						120167, 120168, 120169, 120170, 120171};
		private static final long[] COMBINED_DIACRITICALS =
				{0x003C020D2L, 0x003D020E5L, 0x003E020D2L, 0x00660006AL, 0x205F0200AL, 0x219D00338L, 0x220200338L,
						0x2220020D2L, 0x22290FE00L, 0x222A0FE00L, 0x223C020D2L, 0x223D00331L, 0x223E00333L, 0x224200338L,
						0x224200338L, 0x224B00338L, 0x224D020D2L, 0x224E00338L, 0x224E00338L, 0x224F00338L, 0x224F00338L,
						0x225000338L, 0x2261020E5L, 0x2264020D2L, 0x2265020D2L, 0x226600338L, 0x226600338L, 0x226700338L,
						0x226700338L, 0x226700338L, 0x22680FE00L, 0x22680FE00L, 0x22690FE00L, 0x22690FE00L, 0x226A00338L,
						0x226A00338L, 0x226A020D2L, 0x226B00338L, 0x226B00338L, 0x226B020D2L, 0x227F00338L, 0x2282020D2L,
						0x2282020D2L, 0x2282020D2L, 0x2283020D2L, 0x2283020D2L, 0x2283020D2L, 0x228A0FE00L, 0x228A0FE00L,
						0x228B0FE00L, 0x228B0FE00L, 0x228F00338L, 0x229000338L, 0x22930FE00L, 0x22940FE00L, 0x22B4020D2L,
						0x22B5020D2L, 0x22D800338L, 0x22D900338L, 0x22DA0FE00L, 0x22DB0FE00L, 0x22F500338L, 0x22F900338L,
						0x293300338L, 0x29CF00338L, 0x29D000338L, 0x2A6D00338L, 0x2A7000338L, 0x2A7D00338L, 0x2A7D00338L,
						0x2A7D00338L, 0x2A7E00338L, 0x2A7E00338L, 0x2A7E00338L, 0x2AA100338L, 0x2AA200338L, 0x2AAC0FE00L,
						0x2AAD0FE00L, 0x2AAF00338L, 0x2AAF00338L, 0x2AAF00338L, 0x2AB000338L, 0x2AB000338L, 0x2AB000338L,
						0x2AC500338L, 0x2AC500338L, 0x2AC600338L, 0x2AC600338L, 0x2ACB0FE00L, 0x2ACB0FE00L, 0x2ACC0FE00L,
						0x2ACC0FE00L, 0x2AFD020E5L};

		private static final int MIN_ESCAPE;
		private static final int MAX_ESCAPE;
		private static final HashMap<String, int[]> LOOKUP_MAP;

		static {
			int minEscape = Integer.MAX_VALUE;
			int maxEscape = Integer.MIN_VALUE;
			HashMap<String, int[]> lookupMap = new HashMap<>(NAMES.length);

			for (String name : NAMES) {
				minEscape = Math.min(minEscape, name.length());
				maxEscape = Math.max(maxEscape, name.length());
			}

			for (int i = 0; i < CODEPOINTS.length; i++)
				lookupMap.put(NAMES[i], new int[]{CODEPOINTS[i]});

			for (int i = 0; i < COMBINED_DIACRITICALS.length; i++) {
				long combinedDiacritical = COMBINED_DIACRITICALS[i];
				int codepoint1 = (int) (combinedDiacritical >> 20);
				int codepoint2 = (int) (combinedDiacritical & 0xFFFFF);
				lookupMap.put(NAMES[CODEPOINTS.length + i], new int[]{codepoint1, codepoint2});
			}

			MIN_ESCAPE = minEscape;
			MAX_ESCAPE = maxEscape;
			LOOKUP_MAP = lookupMap;
		}

		public static String unescapeHtml(String input) {
			StringBuilder result = null;

			int len = input.length();
			int start = 0;
			int escStart = 0;
			while (true) {
				// Look for '&'
				while (escStart < len && input.charAt(escStart) != '&')
					escStart++;

				if (escStart == len)
					break;

				escStart++;

				// Found '&'. Look for ';'
				int escEnd = escStart;
				while (escEnd < len && escEnd - escStart < MAX_ESCAPE + 1 && input.charAt(escEnd) != ';')
					escEnd++;

				if (escEnd == len)
					break;

				// Bail if this is not a potential HTML entity.
				if (escEnd - escStart < MIN_ESCAPE || escEnd - escStart == MAX_ESCAPE + 1) {
					escStart++;
					continue;
				}

				// Check the kind of entity
				if (input.charAt(escStart) == '#') {
					// Numeric entity
					int numStart = escStart + 1;
					int radix;

					char firstChar = input.charAt(numStart);
					if (firstChar == 'x' || firstChar == 'X') {
						numStart++;
						radix = 16;
					} else {
						radix = 10;
					}

					try {
						int entityValue = Integer.parseInt(input.substring(numStart, escEnd), radix);

						if (result == null)
							result = new StringBuilder(input.length());

						result.append(input, start, escStart - 1);

						if (entityValue > 0xFFFF)
							result.append(Character.toChars(entityValue));
						else
							result.append((char) entityValue);
					} catch (NumberFormatException ignored) {
						escStart++;
						continue;
					}
				} else {
					// Named entity
					int[] codePoints = LOOKUP_MAP.get(input.substring(escStart, escEnd));
					if (codePoints == null) {
						escStart++;
						continue;
					}

					if (result == null)
						result = new StringBuilder(input.length());

					result.append(input, start, escStart - 1);
					for (int codePoint : codePoints)
						result.appendCodePoint(codePoint);
				}

				// Skip escape
				start = escEnd + 1;
				escStart = start;
			}

			if (result != null) {
				result.append(input, start, len);
				return result.toString();
			}

			return input;
		}
	}

	// *** END HTML-Unescaper source ***
}
