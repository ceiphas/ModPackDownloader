package com.nincraft.modpackdownloader;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.nincraft.modpackdownloader.container.CurseMod;
import com.nincraft.modpackdownloader.container.ModContainer;
import com.nincraft.modpackdownloader.util.Reference;
import com.nincraft.modpackdownloader.util.URLHelper;

import lombok.val;

public class ModPackDownloader {

	private static int DOWNLOAD_COUNT = 1;
	static Logger logger = LogManager.getRootLogger();

	public static void main(final String[] args) {
		if (args.length < 2) {
			logger.error("Arguments required: manifest file location, mod download location");
			return;
		} else {
			processArguments(args);
		}

		setupRepo();

		if (Reference.updateMods) {
			logger.info(String.format("Updating mods with parameters: {}, {}, {}", Reference.manifestFile,
					Reference.mcVersion, Reference.releaseType));
			ModUpdater.updateCurseMods(Reference.manifestFile, Reference.mcVersion, Reference.releaseType);
			logger.info("Finished updating mods.");
		} else {
			logger.info(String.format("Starting download with parameters: {}, {}", Reference.manifestFile,
					Reference.modFolder));
			downloadMods(Reference.manifestFile, Reference.modFolder);
			logger.info("Finished downloading mods.");
		}
	}

	private static void processArguments(final String[] args) {
		Reference.manifestFile = args[0];
		Reference.modFolder = args[1];

		if (args.length > 2) {
			for (val arg : args) {
				processArgument(arg);
			}
		}
	}

	private static void processArgument(final String arg) {
		if (arg.equals("-forceDownload")) {
			Reference.forceDownload = true;
			logger.debug("Downloads are now being forced.");
		} else if (arg.equals("-updateMods")) {
			Reference.updateMods = true;
			logger.debug("mods will be updated instead of downloaded.");
		} else if (arg.startsWith("-mcVersion")) {
			Reference.mcVersion = arg.substring(arg.lastIndexOf("=") + 1);
			logger.debug(String.format("Minecraft Version set to: {}", Reference.mcVersion));
		} else if (arg.startsWith("-releaseType")) {
			Reference.releaseType = arg.substring(arg.lastIndexOf("=") + 1);
			logger.debug(String.format("Checking against mod release type: {}", Reference.releaseType));
		} else if (arg.equals("-generateUrlTxt")) {
			Reference.generateUrlTxt = true;
			logger.debug("Mod URL Text files will now be generated.");
		}
	}

	private static void setupRepo() {
		logger.info("Setting up local repository...");
		Reference.userhome = System.getProperty("user.home");
		logger.debug(String.format("User Home System Property detected as: {}", Reference.userhome));

		Reference.os = System.getProperty("os.name");
		logger.debug(String.format("Operating System detected as: {}", Reference.os));

		if (Reference.os.startsWith("Windows")) {
			Reference.userhome += Reference.WINDOWS_FOLDER;
		} else if (Reference.os.startsWith("Mac")) {
			Reference.userhome += Reference.MAC_FOLDER;
		} else {
			Reference.userhome += Reference.OTHER_FOLDER;
		}
		logger.debug(String.format("User Home Folder set to: {}", Reference.userhome));

		createFolder(Reference.userhome);
		logger.info("Finished setting up local repository.");
	}

	private static void downloadMods(final String manifestFile, final String modFolder) {
		downloadCurseMods(manifestFile, modFolder);
		downloadThirdPartyMods(manifestFile, modFolder);
	}

	private static void downloadThirdPartyMods(final String manifestFile, final String modFolder) {
		try {
			val jsons = (JSONObject) new JSONParser().parse(new FileReader(manifestFile));
			val urlList = (JSONArray) jsons.get("thirdParty");

			if (urlList != null) {
				logger.info("Starting download of " + urlList.size() + " 3rd party mods");
				DOWNLOAD_COUNT = 1;

				for (val item : urlList) {
					val urlJson = (JSONObject) item;
					val url = (String) urlJson.get("url");
					val projectName = (String) urlJson.get("name");
					val fileName = urlJson.get("rename") == null
							? url.substring(url.lastIndexOf("/") + 1, url.lastIndexOf(".jar") + 4)
							: (String) urlJson.get("rename");

					logger.info(
							String.format("Downloading {}. Mod {} of {}", fileName, DOWNLOAD_COUNT, urlList.size()));
					downloadFile(url, modFolder, fileName, projectName, false);
					DOWNLOAD_COUNT++;
				}
			}
		} catch (final IOException | ParseException e) {
			logger.error(e.getMessage());
		}
	}

	private static void downloadCurseMods(final String manifestFile, final String modFolder) {
		try {
			val jsonObject = (JSONObject) new JSONParser().parse(new FileReader(manifestFile));
			val fileList = (JSONArray) (jsonObject.containsKey("curseFiles") ? jsonObject.get("curseFiles")
					: jsonObject.get("files"));

			if (fileList != null) {
				logger.info(String.format("Starting download of {} mods from Curse.", fileList.size()));
				DOWNLOAD_COUNT = 1;
				for (val file : fileList) {
					val mod = new CurseMod((JSONObject) file);

					val conn = (HttpURLConnection) new URL(mod.getProjectURL()).openConnection();
					conn.setInstanceFollowRedirects(false);
					conn.connect();

					mod.setFolder(modFolder);
					mod.setProjectName(conn.getHeaderField("Location").split("/")[2]);

					logger.info(String.format("Downloading {}. Mod {} of {}", mod.getProjectName(), DOWNLOAD_COUNT,
							fileList.size()));
					downloadCurseForgeFile(mod);
					DOWNLOAD_COUNT++;
				}
			}
		} catch (final IOException e) {
			logger.error(e.getMessage());
		} catch (final ParseException e) {
			logger.error(e.getMessage());
		}
	}

	private static void createFolder(final String folder) {
		if (folder != null) {
			final File dir = new File(folder);
			if (!dir.exists()) {
				dir.mkdirs();
			}
		}
	}

	private static void downloadCurseForgeFile(final ModContainer mod) {
		try {
			val fileName = mod.getRename() == null
					? getCurseForgeDownloadLocation(mod.getUrl(), mod.getProjectName(), mod.getProjectName())
					: mod.getRename();

			downloadFile(mod.getUrl(), mod.getFolder(), fileName, mod.getProjectName(), false);
		} catch (final MalformedURLException e) {
			logger.error(e.getMessage());
		} catch (final FileNotFoundException e) {
			logger.error(String.format("Could not find: {}", mod.getProjectName()), e);
		} catch (final IOException e) {
			logger.error(e.getMessage());
		}
	}

	private static void downloadFile(final String url, final String folder, final String fileName,
			final String projectName, final boolean useUserAgent) throws MalformedURLException, FileNotFoundException {
		try {
			val decodedFileName = URLHelper.decodeSpaces(fileName);
			if (!isInLocalRepo(projectName, decodedFileName) || Reference.forceDownload) {
				ReadableByteChannel rbc;

				if (useUserAgent) {
					val conn = (HttpURLConnection) new URL(url).openConnection();
					conn.addRequestProperty("User-Agent", "Mozilla/4.0");
					rbc = Channels.newChannel(conn.getInputStream());
				} else {
					rbc = Channels.newChannel(new URL(url).openStream());
				}

				File downloadedFile;
				if (folder != null) {
					createFolder(folder);
					downloadedFile = new File(folder + File.separator + decodedFileName);
				} else {
					downloadedFile = new File(decodedFileName);
				}

				val fos = new FileOutputStream(downloadedFile);
				fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
				fos.close();

				if (Reference.generateUrlTxt) {
					generateUrlTxt(downloadedFile, url, folder);
				}

				copyToLocalRepo(projectName, downloadedFile);
			} else {
				copyFromLocalRepo(projectName, decodedFileName, folder);
			}
		} catch (final IOException e) {
			if (!useUserAgent) {
				logger.warn(
						String.format("Error getting {}. Attempting to redownload using alternate method.", fileName));
				downloadFile(url, folder, fileName, projectName, true);
			} else {
				logger.error("Could not download " + fileName, e.getMessage());
			}
		}
	}

	private static void generateUrlTxt(final File downloadedFile, final String url, final String folder) {
		if (folder != null) {
			new File(folder + File.separator + downloadedFile.getName() + ".url.txt");
		} else {
			new File(downloadedFile.getName() + "url.txt");
		}
	}

	private static void copyToLocalRepo(final String projectName, final File downloadedFile) {
		val newProjectName = projectName != null ? projectName : "thirdParty";
		try {
			val localRepoFolder = new File(Reference.userhome + newProjectName);
			FileUtils.copyFileToDirectory(downloadedFile, localRepoFolder);
		} catch (final IOException e) {
			logger.error(String.format("Could not copy {} to local repo.", newProjectName), e);
		}
	}

	private static void copyFromLocalRepo(String projectName, final String fileName, final String folder) {
		try {
			if (projectName == null) {
				projectName = "thirdParty";
			}
			final File localRepoMod = new File(Reference.userhome + projectName + File.separator + fileName);
			FileUtils.copyFileToDirectory(localRepoMod, new File(folder));
		} catch (final IOException e) {
			logger.error("Could not copy " + projectName + " from local repo", e);
		}
	}

	private static boolean isInLocalRepo(String projectName, final String fileName) {
		if (projectName == null) {
			projectName = "thirdParty";
		}
		final File localCheck = new File(Reference.userhome + projectName + File.separator + fileName);
		return localCheck.exists();
	}

	private static String getCurseForgeDownloadLocation(final String url, final String projectName,
			final String downloadLocation) throws IOException, MalformedURLException {
		String encodedDownloadLocation = URLHelper.encodeSpaces(downloadLocation);

		if (encodedDownloadLocation.indexOf(Reference.JAR_FILE_EXT) == -1) {
			val newUrl = url + Reference.COOKIE_TEST_1;

			HttpURLConnection conn = (HttpURLConnection) new URL(newUrl).openConnection();
			conn.setInstanceFollowRedirects(false);
			conn.connect();

			String actualURL = conn.getURL().toString();
			int retryCount = 0;

			while (conn.getResponseCode() != 200 || actualURL.indexOf(Reference.JAR_FILE_EXT) == -1) {
				val headerLocation = conn.getHeaderField("Location");
				if (headerLocation != null) {
					actualURL = headerLocation;
				} else {
					actualURL = conn.getURL().toString();
				}

				if (retryCount > Reference.RETRY_COUNTER) {
					break;
				}

				conn = (HttpURLConnection) new URL(newUrl).openConnection();
				retryCount++;
			}

			if (actualURL.substring(actualURL.lastIndexOf(Reference.URL_DELIMITER) + 1)
					.indexOf(Reference.JAR_FILE_EXT) != -1) {
				encodedDownloadLocation = actualURL.substring(actualURL.lastIndexOf(Reference.URL_DELIMITER) + 1);
			} else {
				encodedDownloadLocation = projectName + Reference.JAR_FILE_EXT;
			}
		}

		return URLHelper.decodeSpaces(encodedDownloadLocation);
	}

}
