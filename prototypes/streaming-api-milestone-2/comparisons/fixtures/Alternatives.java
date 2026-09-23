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
 */

package fixtures;

import com.soklet.ResponseStream;
import com.soklet.StreamResourceFactory;

/** Caller-shape comparisons only; provider and candidate APIs have no runtime implementation. */
public final class Alternatives {

	private Alternatives() {}

	static void selectedCloseOnCancel(ResponseStream responseStream, Pages pages) throws Exception {
		var page = responseStream.open(pages::open);
		responseStream.writeUtf8(page.text());
	}

	static void policyCloseOnCancel(PolicyStream responseStream, Pages pages) throws Exception {
		var page = responseStream.open(pages::open, ResourcePolicy.closeOnCancel());
		responseStream.writeUtf8(page.text());
	}

	static void selectedExplicitAbort(ResponseStream responseStream, Pages pages) throws Exception {
		var page = responseStream.open(pages::open, Page::cancel);
		responseStream.writeUtf8(page.text());
	}

	static void policyExplicitAbort(PolicyStream responseStream, Pages pages) throws Exception {
		var page = responseStream.open(pages::open, ResourcePolicy.abortWith(Page::cancel));
		responseStream.writeUtf8(page.text());
	}

	static void policyContravariancePreservesResourceType(PolicyStream responseStream, Pages pages) throws Exception {
		ResourcePolicy<AutoCloseable> policy = ResourcePolicy.closeOnCancel();
		var page = responseStream.open(pages::open, policy);
		responseStream.writeUtf8(page.text());
	}

	static void selectedOnePage(ResponseStream responseStream, Pages pages) throws Exception {
		responseStream.using(pages::open, page ->
				responseStream.writeUtf8(page.text()));
	}

	static void lexicalOnePage(LexicalStream lexicalStream, Pages pages) throws Exception {
		lexicalStream.within(responseStream -> {
			var page = responseStream.open(pages::open);
			responseStream.writeUtf8(page.text());
		});
	}

	static void selectedPageAndParser(ResponseStream responseStream, Pages pages) throws Exception {
		responseStream.using(pages::open, page -> {
			// All nested acquisitions belong to this lexical frame. The parser closes
			// before the page; this parser does not itself close its input page.
			var parser = responseStream.own(new NonOwningParser(page));
			responseStream.writeUtf8(parser.text());
		});
	}

	static void lexicalPageAndParser(LexicalStream lexicalStream, Pages pages) throws Exception {
		lexicalStream.within(responseStream -> {
			var page = responseStream.open(pages::open);
			var parser = responseStream.own(new NonOwningParser(page));
			responseStream.writeUtf8(parser.text());
		});
	}

	/** The contravariant policy does not force a concrete factory result to widen to AutoCloseable. */
	private interface PolicyStream {
		<T extends AutoCloseable> T open(StreamResourceFactory<? extends T> factory,
				ResourcePolicy<? super T> policy) throws Exception;

		void writeUtf8(String text) throws Exception;
	}

	private interface ResourcePolicy<T extends AutoCloseable> {
		static <T extends AutoCloseable> ResourcePolicy<T> closeOnCancel() {
			throw new UnsupportedOperationException("Compile-only policy candidate");
		}

		static <T extends AutoCloseable> ResourcePolicy<T> abortWith(ResponseStream.ResourceAborter<? super T> aborter) {
			throw new UnsupportedOperationException("Compile-only policy candidate");
		}
	}

	/** A candidate lexical frame can expose the existing ResponseStream type to its block. */
	private interface LexicalStream {
		void within(CheckedBlock block) throws Exception;
	}

	@FunctionalInterface
	private interface CheckedBlock {
		void run(ResponseStream responseStream) throws Exception;
	}

	private interface Pages {
		Page open() throws Exception;
	}

	private static final class Page implements AutoCloseable {
		String text() throws Exception {
			throw new UnsupportedOperationException("Compile-only provider");
		}

		void cancel() throws Exception {
			throw new UnsupportedOperationException("Compile-only provider");
		}

		@Override
		public void close() throws Exception {
			throw new UnsupportedOperationException("Compile-only provider");
		}
	}

	/** A parsing view whose close releases parser state without closing the page. */
	private static final class NonOwningParser implements AutoCloseable {
		NonOwningParser(Page page) throws Exception {
			throw new UnsupportedOperationException("Compile-only provider");
		}

		String text() throws Exception {
			throw new UnsupportedOperationException("Compile-only provider");
		}

		@Override
		public void close() throws Exception {
			throw new UnsupportedOperationException("Compile-only provider");
		}
	}
}
