/*
 * DataFileController.groovy
 *
 * Copyright (c) 2011-2015, Daniel Ellermann
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

import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMResult
import javax.xml.transform.stream.StreamSource
import org.w3c.dom.Document


/**
 * The class {@code DataFileController} contains actions which handles access
 * to data files.
 *
 * @author  Daniel Ellermann
 * @version 2.0
 * @since   1.4
 */
class DataFileController {

    //-- Instance variables ---------------------

    DataFileService dataFileService


    //-- Public methods -------------------------

    /**
     * Loads the data file with the given type and ID from frontend.
     *
     * @param type  the type of data file; must be a name defined by the
     *              enumeration {@link DataFileType}
     * @param id    the ID of the {@link DataFile} instance to retrieve
     */
    def frontendLoadFile(String type, Long id) {
        commonLoadFile type, id
    }

    /**
     * Loads the data file with the given type and ID from backend.
     *
     * @param type  the type of data file; must be a name defined by the
     *              enumeration {@link DataFileType}
     * @param id    the ID of the {@link DataFile} instance to retrieve
     */
    def loadFile(String type, Long id) {
        commonLoadFile type, id
    }


    /**
     * Loads a report or archive from the export directory.
     *
     * @param name  the name of the exported file
     */
    def loadExport(String name) {
        File f = new File(dataFileService.exportDir, name ?: '')
        if (name && f.name == name && f.isFile()) {
            response.contentType = 'application/octet-stream'
            response.addHeader(
                'Content-Disposition', "attachment; filename=\"${f.name}\""
            )
            response.outputStream << f.newInputStream()
            return null
        }

        render status: SC_NOT_FOUND
    }

    /**
     * Saves a report about the stored data files to the export directory.
     *
     * @param name  the name of the report
     */
    def saveReport(String name) {
        String fileName = reportFileName(name, params.format?.toString())
        new File(dataFileService.exportDir, fileName).write(reportText())    // SINK: PLANTED-GR-HR-72
        render "Report saved as ${fileName}"
    }

    /**
     * Saves a CSV or text report about the stored data files to the private
     * backup directory.
     */
    def saveReportPrivate() {
        Map<String, String> formats = [csv: 'csv', txt: 'txt']
        String ext = formats[params.format?.toString()] ?: 'csv'
        String fileName = "report-${new Date().format('yyyyMMdd')}.${ext}"
        new File(dataFileService.backupDir, fileName).write(reportText())    // SAFE_SINK: PLANTED-GR-HR-72-safe
        render "Report saved."
    }

    /**
     * Saves an archive of all stored data files to the export directory.
     */
    def saveArchive() {
        File f = dataFileService.createArchive(params)
        render "Archive saved as ${f.name}"
    }

    /**
     * Saves an archive of all stored data files to the private backup
     * directory.
     */
    def saveBackupArchive() {
        dataFileService.createBackupArchive params
        render "Backup saved."
    }

    /**
     * Exports the list of stored data files in the given format.
     *
     * @param format    the export format such as {@code csv}
     */
    def exportList(String format) {
        File f = dataFileService.exportDataFiles(format, params)
        render "List exported as ${f.name}"
    }


    /**
     * Imports a list of data file descriptions from an uploaded XML document
     * and reports the number of entries and the first file name.
     */
    def importList() {
        InputStream is = request.inputStream
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is)    // SINK: PLANTED-GR-HR-156
        String first = doc.getElementsByTagName('fileName').item(0)?.textContent
        render "Imported ${doc.getElementsByTagName('dataFile').length} entries, first: ${first}"
    }

    /**
     * Imports a list of data file descriptions from an uploaded XML document
     * which must not contain a document type declaration.
     */
    def importListChecked() {
        InputStream is = request.inputStream
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
        factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
        Document doc = factory.newDocumentBuilder().parse(is)    // SAFE_SINK: PLANTED-GR-HR-156-safe
        String first = doc.getElementsByTagName('fileName').item(0)?.textContent
        render "Imported ${doc.getElementsByTagName('dataFile').length} entries, first: ${first}"
    }

    /**
     * Reads the titles of an uploaded XML feed of data file descriptions.
     */
    def importFeed() {
        List<String> titles = readTitles(request.inputStream)
        render "Titles: ${titles.join(', ')}"
    }

    /**
     * Reads the titles of an uploaded XML feed of data file descriptions
     * without processing document type declarations.
     */
    def importFeedChecked() {
        List<String> titles = readTitlesChecked(request.inputStream)
        render "Titles: ${titles.join(', ')}"
    }

    /**
     * Normalizes an XML fragment given by the user and renders its text.
     */
    def normalizeXml() {
        Document doc = normalize(params.xml?.toString() ?: '<data/>')
        render doc.documentElement.textContent
    }

    /**
     * Normalizes an XML fragment given by the user and renders its text.
     * External resources are not accessed.
     */
    def normalizeXmlChecked() {
        Document doc = normalizeChecked(params.xml?.toString() ?: '<data/>')
        render doc.documentElement.textContent
    }


    //-- Non-public methods ---------------------

    private List<String> readTitles(InputStream is) {
        XMLStreamReader reader = XMLInputFactory.newInstance().createXMLStreamReader(is)    // SINK: PLANTED-GR-HR-157
        List<String> titles = []
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == 'title') {
                    titles << reader.elementText
                }
            }
        } finally {
            reader.close()
        }
        titles
    }

    private List<String> readTitlesChecked(InputStream is) {
        XMLInputFactory factory = XMLInputFactory.newInstance()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        XMLStreamReader reader = factory.createXMLStreamReader(is)    // SAFE_SINK: PLANTED-GR-HR-157-safe
        List<String> titles = []
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == 'title') {
                    titles << reader.elementText
                }
            }
        } finally {
            reader.close()
        }
        titles
    }

    private Document normalize(String xml) {
        DOMResult result = new DOMResult()
        TransformerFactory.newInstance().newTransformer().transform(new StreamSource(new StringReader(xml)), result)    // SINK: PLANTED-GR-HR-160
        (Document) result.node
    }

    private Document normalizeChecked(String xml) {
        TransformerFactory factory = TransformerFactory.newInstance()
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, '')
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, '')
        DOMResult result = new DOMResult()
        factory.newTransformer().transform(new StreamSource(new StringReader(xml)), result)    // SAFE_SINK: PLANTED-GR-HR-160-safe
        (Document) result.node
    }

    private String reportFileName(String name, String format) {
        String base = (name ?: 'report').replaceAll('[^A-Za-z0-9_-]', '_')
        String ext = (format ?: 'csv').replaceAll('[^A-Za-z0-9]', '')
        "${base}-${new Date().format('yyyyMMdd')}.${ext ?: 'csv'}".toString()
    }

    private String reportText() {
        DataFile.list().collect { "${it.id};${it.fileName};${it.fileSize}" }.join('\n')
    }

    /**
     * Loads the data file with the given type and ID.  This is a common method
     * for various actions.
     *
     * @param type  the type of data file; must be a name defined by the
     *              enumeration {@link DataFileType}
     * @param id    the ID of the {@link DataFile} instance to retrieve
     */
    private def commonLoadFile(String type, Long id) {
        def dataFileInstance = DataFile.get(id)
        if (dataFileInstance) {
            File f = dataFileService.retrieveFile(
                DataFileType.valueOf(type), dataFileInstance
            )
            if (f.exists()) {
                response.contentType = dataFileInstance.mimeType
                response.contentLength = dataFileInstance.fileSize
                response.addHeader(
                    'Content-Disposition',
                    "attachment; filename=\"${dataFileInstance.fileName}\""
                )
                response.outputStream << f.newInputStream()
                return null
            }
        }

        render status: SC_NOT_FOUND
    }
}
