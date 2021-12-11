package health.ere.extract;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.swing.JFileChooser;
import javax.swing.JFrame;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Medication;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Patient;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;

public class CsvFromPdfExtractor {

    public static String utilJavaScript = "var print = function (s) { __newOut.print(s); }; var println = function (s) { __newOut.println(s); };";
    
    private static Logger log = Logger.getLogger(CsvFromPdfExtractor.class.getName());

    static IParser xmlParser = FhirContext.forR4().newXmlParser();

    static Pattern BUNDLE_MATCHER = Pattern.compile(".*(<Bundle.*</Bundle>).*", Pattern.MULTILINE | Pattern.DOTALL);

    static Pattern ACCESS_CODE_MATCHER = Pattern.compile(".*<accessCode>(.*)</accessCode>.*", Pattern.MULTILINE | Pattern.DOTALL);

    static ScriptEngineManager mgr = new ScriptEngineManager();
    static ScriptEngine jsEngine = mgr.getEngineByName("JavaScript");

    static FileOutputStream fos;
    
    public static void main(String[] args) {

        JFileChooser fc = new JFileChooser();
        JFrame frame = new JFrame();
        File defaultDir = new File("/home/manuel/git/secret-test-print-samples/Packages/Demo");
        if(defaultDir.exists()) {
            fc.setCurrentDirectory(defaultDir);
        }
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setAcceptAllFileFilterUsed(false);
        fc.showOpenDialog(frame);
        File folder = fc.getSelectedFile();
        
        try (Stream<Path> paths = Files.walk(folder.toPath())) {
            fos = new FileOutputStream("output.csv", true);
            paths
                    .filter(Files::isRegularFile)
                    .filter(f -> f.toFile().getAbsolutePath().endsWith(".pdf"))
                    .forEach(f -> {
                        try {
                            PDDocument document = PDDocument.load(f.toFile());
                            PDDocumentNameDictionary namesDictionary = 
                                    new PDDocumentNameDictionary( document.getDocumentCatalog() );
                            PDEmbeddedFilesNameTreeNode efTree = namesDictionary.getEmbeddedFiles();
                            if (efTree != null) {
                                Map<String, PDComplexFileSpecification> names = efTree.getNames();
                                if (names != null) {
                                    extractFiles(names, f);
                                } else {
                                    List<PDNameTreeNode<PDComplexFileSpecification>> kids = efTree.getKids();
                                    for (PDNameTreeNode<PDComplexFileSpecification> node : kids) {
                                        names = node.getNames();
                                        extractFiles(names, f);
                                    }
                                }
                            }
                            document.close();
                        } catch (IOException ex) {
                            log.log(Level.WARNING, "Could read all files", ex);
                        }
                    });
            fos.close();
        } catch (IOException ex) {
            log.log(Level.WARNING, "Could read all files", ex);
        }
        System.exit(0);
    }


    private static void extractFiles(Map<String, PDComplexFileSpecification> names, Path f) 
            throws IOException {
        for (Entry<String, PDComplexFileSpecification> entry : names.entrySet()) {
            String filename = entry.getKey();
            PDComplexFileSpecification fileSpec = entry.getValue();
            PDEmbeddedFile embeddedFile = getEmbeddedFile(fileSpec);
            extractFile(filename, embeddedFile, f);
        }
    }

    private static void extractFile(String filename, PDEmbeddedFile embeddedFile, Path f)
            throws IOException {
        
        try (InputStream inputStream = embeddedFile.createInputStream()) {
            String xml = new String(inputStream.readAllBytes(), "UTF-8");
            Matcher m1 = BUNDLE_MATCHER.matcher(xml);
            Matcher m2 = ACCESS_CODE_MATCHER.matcher(xml);
            if(m1.matches() && m2.matches()) {
                String bundleString = m1.group(1);
                String accessCode = m2.group(1);
                try {
                    Bundle bundle = xmlParser.parseResource(Bundle.class, bundleString);
                    log.fine(accessCode+" "+bundle.getId());
                    
                    ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream(8192);
                    PrintStream newOut = new PrintStream(outputBuffer, true);
                    Bindings bindings = jsEngine.createBindings();
                    bindings.put("__newOut", newOut);
                    bindings.put("accessCode", accessCode);
                    bindings.put("bundle", bundle);
                    try {
                        MedicationRequest medicationRequest = ((MedicationRequest)bundle.getEntry().stream().filter(e -> e.getResource() instanceof MedicationRequest).findAny().get().getResource());
                        bindings.put("medicationRequest", medicationRequest);
                    } catch(NoSuchElementException ex) {
                        ex.printStackTrace();
                    }
                    try {
                        Medication medication = ((Medication)bundle.getEntry().stream().filter(e -> e.getResource() instanceof Medication).findAny().get().getResource());
                        bindings.put("medication", medication);
                    } catch(NoSuchElementException ex) {
                        ex.printStackTrace();
                    }
                    try {
                        Patient patient = ((Patient)bundle.getEntry().stream().filter(e -> e.getResource() instanceof Patient).findAny().get().getResource());
                        bindings.put("patient", patient);
                    } catch(Exception ex) {
                        ex.printStackTrace();
                    }
                    try {
                        Coverage coverage = ((Coverage)bundle.getEntry().stream().filter(e -> e.getResource() instanceof Coverage).findAny().get().getResource());
                        bindings.put("coverage", coverage);
                    } catch(Exception ex) {
                        ex.printStackTrace();
                    }
                    bindings.put("pdfFile", f.toFile());
                    
                    jsEngine.eval(utilJavaScript + Files.readString(Paths.get("process-bundle.js")), bindings);
                    newOut.close();
                    String returnString = outputBuffer.toString();
                    try {
                        outputBuffer.close();
                    } catch (IOException e) {
                        log.log(Level.SEVERE, "Problem closing buffer", e);
                    }
                    fos.write((returnString).getBytes());
                    
                } catch (DataFormatException | ScriptException ex) {
                    log.info(bundleString);
                    log.log(Level.WARNING, "Could read "+f.toFile().getAbsolutePath(), ex);
                }
            }
            
        } catch (IOException ex) {
            log.log(Level.WARNING, "Could read all files", ex);
        }
        
    }

    private static PDEmbeddedFile getEmbeddedFile(PDComplexFileSpecification fileSpec ) {
        // search for the first available alternative of the embedded file
        PDEmbeddedFile embeddedFile = null;
        if (fileSpec != null) {
            embeddedFile = fileSpec.getEmbeddedFileUnicode(); 
            if (embeddedFile == null) {
                embeddedFile = fileSpec.getEmbeddedFileDos();
            }
            if (embeddedFile == null) {
                embeddedFile = fileSpec.getEmbeddedFileMac();
            }
            if (embeddedFile == null) {
                embeddedFile = fileSpec.getEmbeddedFileUnix();
            }
            if (embeddedFile == null) {
                embeddedFile = fileSpec.getEmbeddedFile();
            }
        }
        return embeddedFile;
    }
}