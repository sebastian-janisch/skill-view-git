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
package org.sjanisch.skillview.git;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Objects;

import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.sjanisch.skillview.core.diff.api.ContentDiff;
import org.sjanisch.skillview.core.utility.Lazy;

/**
 * Implements using {@link DiffAlgorithm} using
 * {@link RawTextComparator#WS_IGNORE_ALL}.
 * <p>
 * Content is classified as 'touched' if it was either inserted or modified.
 * <p>
 * This implementation is thread safe.
 * 
 * @author sebastianjanisch
 *
 */
public class GitAlgorithmContentDiff implements ContentDiff {

	private final Lazy<Collection<String>> diff;

	/**
	 * 
	 * @param previousContent
	 *            must not be {@code null}
	 * @param currentContent
	 *            must not be {@code null}
	 * @param algorithm
	 *            must not be {@code null}
	 */
	public GitAlgorithmContentDiff(String previousContent, String currentContent, DiffAlgorithm algorithm) {
		Objects.requireNonNull(previousContent, "previousContent");
		Objects.requireNonNull(currentContent, "currentContent");
		Objects.requireNonNull(algorithm, "algorithm");

		this.diff = Lazy.of(() -> diff(previousContent, currentContent, algorithm));
	}

	@Override
	public Collection<String> getTouchedContent() {
		return diff.get();
	}

	private static Collection<String> diff(String previousContent, String currentContent, DiffAlgorithm algorithm) {
		RawText previous = new RawText(previousContent.getBytes());
		RawText current = new RawText(currentContent.getBytes());
		EditList edits = algorithm.diff(RawTextComparator.WS_IGNORE_ALL, previous, current);

		Collection<String> result = new LinkedList<>();
		for (int i = 0; i < edits.size(); ++i) {
			Edit edit = edits.get(i);

			switch (edit.getType()) {
			case INSERT:
			case REPLACE:
				String touched = current.getString(edit.getBeginB(), edit.getEndB(), false).trim();
				result.add(touched);
				break;
			default:
				continue;
			}

		}

		return result;
	}

}
