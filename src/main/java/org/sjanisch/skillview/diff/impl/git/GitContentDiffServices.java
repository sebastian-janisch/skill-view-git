/*
MIT License

Copyright (c) 2016 Sebastian Janisch

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */
package org.sjanisch.skillview.diff.impl.git;

import static org.sjanisch.skillview.utility.ExceptionWrappers.unchecked;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm;
import org.sjanisch.skillview.diff.api.ContentDiffService;

/**
 * Various implementations of {@link ContentDiffService}.
 * <p>
 * Implementations offered by this class are thread safe.
 * 
 * @author sebastianjanisch
 *
 */
public class GitContentDiffServices {

	private static final Charset CHARSET = Charset.forName("UTF-8");

	/**
	 * Applies the git {@link SupportedAlgorithm#HISTOGRAM histogram} algorithm.
	 */
	public static ContentDiffService GIT_HISTOGRAM_ALGORITHM = createService(SupportedAlgorithm.HISTOGRAM);

	/**
	 * Applies the git {@link SupportedAlgorithm#MYERS histogram} algorithm.
	 */
	public static ContentDiffService GIT_MYERS_ALGORITHM = createService(SupportedAlgorithm.MYERS);

	private GitContentDiffServices() {
		throw new UnsupportedOperationException("no instances");
	}

	private static ContentDiffService createService(SupportedAlgorithm diffAlgorithm) {
		return (previous, current) -> {
			String previousAsString = toString(previous);
			String currentAsString = toString(current);
			DiffAlgorithm algorithm = DiffAlgorithm.getAlgorithm(diffAlgorithm);
			return new GitAlgorithmContentDiff(previousAsString, currentAsString, algorithm);
		};
	}

	private static String toString(ByteBuffer buffer) {
		return unchecked(() -> CHARSET.newDecoder().decode(buffer)).toString();
	}

}
