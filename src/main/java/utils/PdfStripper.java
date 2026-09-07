package utils;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;

public class PdfStripper {
    public static String extractPlainText(String pdfPath) throws Exception {
        PDDocument document = Loader.loadPDF(new File(pdfPath));
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true); // helps preserve reading order for prose
        String text = stripper.getText(document);
        document.close();
        return text;
    }
}
