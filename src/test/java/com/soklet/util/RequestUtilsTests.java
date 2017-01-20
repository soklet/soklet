/*
 * Copyright 2015 Transmogrify LLC.
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

package com.soklet.util;

import com.soklet.util.RequestUtils.QueryStringParseStrategy;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.1.13
 */
public class RequestUtilsTests {
	@Test
	public void queryStringParsing() {
		Map<String, List<String>> parsedQueryString = RequestUtils.parseQueryString(null, QueryStringParseStrategy.EXCLUDE_NULL_VALUES);
		Assert.assertEquals(Collections.emptyMap(), parsedQueryString);

		parsedQueryString = RequestUtils.parseQueryString("", QueryStringParseStrategy.EXCLUDE_NULL_VALUES);
		Assert.assertEquals(Collections.emptyMap(), parsedQueryString);

		parsedQueryString = RequestUtils.parseQueryString("one", QueryStringParseStrategy.EXCLUDE_NULL_VALUES);
		Assert.assertEquals(Collections.emptyMap(), parsedQueryString);

		parsedQueryString = RequestUtils.parseQueryString("one=  ", QueryStringParseStrategy.EXCLUDE_NULL_VALUES);
		Assert.assertEquals(Collections.emptyMap(), parsedQueryString);

		parsedQueryString = RequestUtils.parseQueryString("one=two&one=thr%20ee&one=fo+ur ", QueryStringParseStrategy.EXCLUDE_NULL_VALUES);
		Assert.assertEquals(new HashMap<String, List<String>>() {{
			put("one", new ArrayList<String>() {{
				add("two");
				add("thr ee");
				add("fo ur");
			}});
		}}, parsedQueryString);

		parsedQueryString = RequestUtils.parseQueryString("one", QueryStringParseStrategy.INCLUDE_NULL_VALUES);
		Assert.assertEquals(new HashMap<String, List<String>>() {{
			put("one", new ArrayList<String>() {{
				add(null);
			}});
		}}, parsedQueryString);
	}
}
