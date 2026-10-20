/*
 * DataFileService.groovy
 *
 * Copyright (c) 2011-2017, Daniel Ellermann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package org.amcworld.springcrm

import grails.core.GrailsApplication
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile


/**
 * The class {@code DataFileService} handles files in the data space.
 *
 * @author  Daniel Ellermann
 * @version 2.1
 * @since   1.4
 */
@Transactional
class DataFileService {

    //-- Fields ---------------------------------

    GrailsApplication grailsApplication


    //-- Public methods -------------------------

    /**
     * Gets the base directory of the data space for the given type of files
     * using the configuration value in key {@code springcrm.dir.data}.
     *
     * @param type  the given type of files
     * @return      the base directory
     */
    File getBaseDir(DataFileType type) {
        getBaseDir type.toString()
    }

    /**
     * Gets the base directory of the data space for the given type of files
     * using the configuration value in key {@code springcrm.dir.data}.  This
     * method is for backward compatibility, only.
     *
     * @param type  the given type of files
     * @return      the base directory
     */
    File getBaseDir(String type) {
        File dir = new File(
            grailsApplication.config.springcrm.dir.data.toString(), type
        )
        if (!dir.exists()) {
            dir.mkdirs()
        }

        dir
    }

    /**
     * Gets the directory in the data space which holds exported reports and
     * archives.  The files in this directory are delivered to the users by
     * action {@code loadExport} of {@link DataFileController}.
     *
     * @return  the export directory
     */
    File getExportDir() {
        getBaseDir 'export'
    }

    /**
     * Gets the private directory for backups.  It is located outside of the
     * data space and is never delivered by any action.
     *
     * @return  the backup directory
     */
    File getBackupDir() {
        File dir = new File(grailsApplication.config.springcrm.dir.base.toString(), 'backup')
        if (!dir.exists()) {
            dir.mkdirs()
            dir.setReadable false, false
            dir.setReadable true, true
            dir.setWritable false, false
            dir.setWritable true, true
        }

        dir
    }

    /**
     * Creates a ZIP archive of all stored data files in the export directory.
     *
     * @param opts  the options; key {@code name} specifies the name of the
     *              archive, key {@code ext} its file extension
     * @return      the created archive
     */
    File createArchive(Map opts) {
        File target = resolveArchiveTarget(opts)
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(target))    // SINK: PLANTED-GR-HR-73
        try {
            addFilesToArchive out
        } finally {
            out.close()
        }

        target
    }

    /**
     * Creates a ZIP archive of all stored data files in the private backup
     * directory.  The archive gets a generated name.
     *
     * @param opts  the options; key {@code name} specifies a label which is
     *              stored as comment in the archive
     * @return      the created archive
     */
    File createBackupArchive(Map opts) {
        File target = resolveBackupTarget()
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(target))    // SAFE_SINK: PLANTED-GR-HR-73-safe
        try {
            out.comment = opts.name?.toString()
            addFilesToArchive out
        } finally {
            out.close()
        }

        target
    }

    /**
     * Exports the list of stored data files using the exporter for the given
     * format.
     *
     * @param format    the export format such as {@code csv}
     * @param opts      the options; key {@code name} specifies the name of the
     *                  export file, key {@code ext} its file extension
     * @return          the created export file
     */
    File exportDataFiles(String format, Map opts) {
        exporterFor(format).export exportDir, opts, DataFile.list()
    }

    /**
     * Removes the given file from the data space and deletes the data file
     * instance from the database.
     *
     * @param type      the type of file to remove
     * @param dataFile  the given data file
     * @return          {@code true} if the file was removed successfully;
     *                  {@code false} otherwise
     */
    boolean removeFile(DataFileType type, DataFile dataFile) {
        retrieveFile(type, dataFile).delete() && dataFile.delete()
    }

    /**
     * Retrieves the underlying file specified by the given data file from the
     * data space.
     *
     * @param type      the type of file to retrieve
     * @param dataFile  the given data file
     * @return          the file object representing the required file on file
     *                  system
     */
    @Transactional(readOnly = true)
    File retrieveFile(DataFileType type, DataFile dataFile) {
        new File(getBaseDir(type), dataFile.storageName)
    }

    /**
     * Stores the given uploaded file to the data space and a {@code DataFile}
     * instance in the database.
     *
     * @param type  the type of file to store
     * @param f     the uploaded file; may be {@code null} or empty
     * @return      the data file containing information about the stored file;
     *              {@code null} if the given file to store is empty
     */
    DataFile storeFile(DataFileType type, MultipartFile f) {
        DataFile dataFile = null
        if (f && !f.empty) {
            dataFile = new DataFile(f)
            dataFile.save failOnError: true
            f.transferTo retrieveFile(type, dataFile)
        }

        dataFile
    }

    private void addFilesToArchive(ZipOutputStream out) {
        for (DataFileType type : DataFileType.values()) {
            for (DataFile dataFile : DataFile.list()) {
                File f = retrieveFile(type, dataFile)
                if (f.exists()) {
                    out.putNextEntry new ZipEntry("${type}/${dataFile.fileName}".toString())
                    out << f.newInputStream()
                    out.closeEntry()
                }
            }
        }
    }

    private File resolveArchiveTarget(Map opts) {
        String name = opts.name ?: 'data-files'
        String ext = opts.ext ?: 'zip'
        new File(exportDir, "${name}.${ext}".toString())
    }

    private File resolveBackupTarget() {
        new File(backupDir, "backup-${UUID.randomUUID()}.zip".toString())
    }

    private DataFileExporter exporterFor(String format) {
        format == 'csv' ? new CsvDataFileExporter() : new ListingDataFileExporter()
    }

    /**
     * Updates the given data file by the stated uploaded file.
     *
     * @param type      the type of file to update
     * @param dataFile  the given data file; if {@code null} a new data file
     *                  will be created
     * @param f         the stated uploaded file; if the file is empty the
     *                  given data file is not updated
     * @return          the updated data file
     */
    DataFile updateFile(DataFileType type, DataFile dataFile, MultipartFile f) {
        if (!dataFile) {
            dataFile = storeFile(type, f)
        } else if (!f.empty) {
            retrieveFile(type, dataFile).delete()
            dataFile.populate f
            dataFile.save failOnError: true
            f.transferTo retrieveFile(type, dataFile)
        }

        dataFile
    }
}


/**
 * The interface {@code DataFileExporter} represents a strategy to export the
 * list of data files to a file.
 */
interface DataFileExporter {
    File export(File dir, Map opts, List<DataFile> dataFiles)
}


/**
 * The class {@code CsvDataFileExporter} writes the list of data files as CSV
 * file.
 */
class CsvDataFileExporter implements DataFileExporter {

    @Override
    File export(File dir, Map opts, List<DataFile> dataFiles) {
        String base = opts.name?.toString()
        String name = (base ==~ /[A-Za-z0-9_-]{1,64}/) ? base : 'data-files'
        File f = new File(dir, name + '.csv')
        f.write(dataFiles.collect { "${it.id};${it.fileName};${it.fileSize}" }.join('\n'))     // SAFE_SINK: PLANTED-GR-HR-74-safe
        f
    }
}


/**
 * The class {@code ListingDataFileExporter} writes the list of data files as
 * plain listing in the format specified by the user.
 */
class ListingDataFileExporter implements DataFileExporter {

    @Override
    File export(File dir, Map opts, List<DataFile> dataFiles) {
        String ext = opts.ext ?: 'txt'
        File f = new File(dir, "${opts.name ?: 'data-files'}.${ext}".toString())
        f.write(dataFiles.collect { "${it.id} ${it.fileName} (${it.fileSize})" }.join('\n'))    // SINK: PLANTED-GR-HR-74
        f
    }
}
