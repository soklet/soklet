/*
 * Copyright 2022-2023 Revetware LLC.
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

package com.soklet.core.impl;

import com.soklet.core.MultipartField;
import com.soklet.core.MultipartParser;
import com.soklet.core.Request;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.soklet.core.Utilities.trimAggressivelyToNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class DefaultMultipartParser implements MultipartParser {
	@Override
	@Nonnull
	public Map<String, Set<MultipartField>> extractMultipartFields(@Nonnull Request request) {
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

		String contentTypeHeader = request.getHeader("Content-Type").get();
		Map<String, String> contentTypeHeaderFields = extractFields(contentTypeHeader);

		ByteArrayInputStream input = new ByteArrayInputStream(requestBody);
		MultipartStream multipartStream = new MultipartStream(input,
				contentTypeHeaderFields.get("boundary").getBytes(), progressNotifier);

		Map<String, Set<MultipartField>> multipartFieldsByName = new HashMap<>();

		try {
			boolean hasNext = multipartStream.skipPreamble();

			while (hasNext) {
				// Example headers:
				//
				// Content-Disposition: form-data; name="doc"; filename="test.pdf"
				// Content-Type: application/pdf
				Map<String, String> allHeaders = splitHeaders(multipartStream.readHeaders());

				String contentDisposition = trimAggressivelyToNull(allHeaders.get("Content-Disposition"));
				Map<String, String> contentDispositionFields = Map.of();

				if (contentDisposition != null)
					contentDispositionFields = new ParameterParser().parse(contentDisposition, ';');

				String name = trimAggressivelyToNull(contentDispositionFields.get("name"));

				if (name == null)
					continue;

				ByteArrayOutputStream data = new ByteArrayOutputStream();
				multipartStream.readBodyData(data);

				String filename = trimAggressivelyToNull(contentDispositionFields.get("filename"));
				String contentType = trimAggressivelyToNull(allHeaders.get("Content-Type"));

				MultipartField multipartField = new MultipartField.Builder(name, data.toByteArray())
						.filename(filename)
						.contentType(contentType)
						.build();

				Set<MultipartField> multipartFields = multipartFieldsByName.get(name);

				if (multipartFields == null) {
					multipartFields = new HashSet<>();
					multipartFieldsByName.put(name, multipartFields);
				}

				multipartFields.add(multipartField);

				hasNext = multipartStream.readBoundary();
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

	protected Map<String, String> splitHeaders(String readHeaders) {
		Map<String, String> headersBuilder = new HashMap<>();
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

	protected Map<String, String> extractFields(String contentTypeHeader) {
		Map<String, String> fieldsBuilder = new HashMap<>();
		String[] contentTypeHeaderParts = contentTypeHeader.split("[;,]");
		for (String contentTypeHeaderPart : contentTypeHeaderParts) {
			String[] kv = contentTypeHeaderPart.split("=");
			if (kv.length == 2) {
				fieldsBuilder.put(kv[0].trim().toLowerCase(Locale.US), kv[1].trim());
			}
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
		 * Determines whether or not a {@code FileItem} instance represents
		 * a simple form field.
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
		 * <p><strong>TODO</strong> allow limiting maximum header size to
		 * protect against abuse.
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
					throw new MalformedStreamException(String.format(
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
				return new HashMap<>();
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
				return new HashMap<>();
			}
			final var params = new HashMap<String, String>();
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
				return new HashMap<>();
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
				return new HashMap<>();
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
}
