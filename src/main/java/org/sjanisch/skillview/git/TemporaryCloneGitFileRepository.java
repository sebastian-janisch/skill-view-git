package org.sjanisch.skillview.git;

import static org.sjanisch.skillview.core.utility.ExceptionWrappers.unchecked;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.EmptyProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exposes a temporary Git file based repository is cloned into a temporary
 * folder upon first access and its {@link AutoCloseable#close() close} method
 * is overridden to delete same temporary folder.
 * 
 * @author sebastianjanisch
 *
 */
public class TemporaryCloneGitFileRepository {

	private static Logger log = LoggerFactory.getLogger(TemporaryCloneGitFileRepository.class);

	/**
	 * 
	 * @param cloneCommand
	 *            a clone command that is configured to access a repository
	 *            which will be cloned into a temporary folder determined by
	 *            this class. Must not be {@code null}.
	 * @return a new file repository based on given clone command for its
	 *         {@link AutoCloseable#close() close} method is overridden to
	 *         delete same temporary folder. Never {@code null}.
	 */
	public static Repository createTemporaryRepository(CloneCommand cloneCommand) {
		AtomicReference<File> tempDir = new AtomicReference<>();
		try {
			tempDir.set(unchecked(() -> Files.createTempDirectory("").toFile()));
			cloneRepository(cloneCommand, tempDir.get());
			return new FileRepository(Paths.get(tempDir.get().getAbsolutePath() + "/.git").toFile()) {
				public void close() {
					super.close();
					String msg = "Deleting git repository from temporary folder %s";
					log.info(String.format(msg, tempDir.get().getAbsolutePath()));
					deleteTempFolder(tempDir.get().toPath());
				}
			};
		} catch (Exception e) {
			if (tempDir.get() != null) {
				deleteTempFolder(tempDir.get().toPath());
			}
			throw new RuntimeException(e);
		}
	}

	private static File cloneRepository(CloneCommand cloneCommand, File tempDir) {
		cloneCommand.setDirectory(tempDir);

		AtomicInteger total = new AtomicInteger();
		cloneCommand.setProgressMonitor(new EmptyProgressMonitor() {
			@Override
			public void beginTask(String title, int totalWork) {
				String msg = "Cloning git repository to temporary folder %s: (%s)";
				log.info(String.format(msg, tempDir.getAbsolutePath(), title));
				total.set(totalWork);
			}

			@Override
			public void endTask() {
				double folderSize = folderSize(tempDir) / 1024.0 / 1024.0;

				String msg = "Successfully cloned %f MB of git repository s to temporary folder %s";
				log.info(String.format(msg, folderSize, tempDir.getAbsolutePath()));
			}
		});

		unchecked(() -> cloneCommand.call());

		return tempDir;
	}

	private static void deleteTempFolder(Path path) {
		try {
			Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
					java.nio.file.Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(final Path file, final IOException e) {
					return handleException(e);
				}

				private FileVisitResult handleException(final IOException e) {
					log.error("Could not delete folder " + path, e);
					return FileVisitResult.TERMINATE;
				}

				@Override
				public FileVisitResult postVisitDirectory(final Path dir, final IOException e) throws IOException {
					if (e != null) {
						return handleException(e);
					}
					java.nio.file.Files.delete(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	};

	private static long folderSize(File directory) {
		long length = 0;
		for (File file : directory.listFiles()) {
			if (file.isFile()) {
				length += file.length();
			} else {
				length += folderSize(file);
			}
		}
		return length;
	}

}
