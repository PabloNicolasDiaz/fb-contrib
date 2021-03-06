package ex;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class STB_Sample {
    public void testSTB(File f1, File f2) throws STBException {
        try {
            InputStream is = new FileInputStream(f1);
        } catch (IOException ioe) {
            throw new STBException();
        }

        try {
            InputStream is = new FileInputStream(f2);
        } catch (IOException ioe) {
            throw new STBException();
        }
    }

    public void testSTB2(File f1, File f2) throws STBException {
        try {
            InputStream is = new FileInputStream(f1);
        } catch (IOException ioe) {
            throw new STBException("It's broken");
        }

        try {
            InputStream is = new FileInputStream(f2);
        } catch (IOException ioe) {
            throw new STBException("It's broken");
        }
    }

    public void testSTB3(File f1, File f2) throws STBException {
        try {
            InputStream is = new FileInputStream(f1);
        } catch (IOException ioe) {
            STBException se = new STBException("It's broken");
            se.initCause(ioe);
            throw se;
        }

        try {
            InputStream is = new FileInputStream(f2);
        } catch (IOException ioe) {
            STBException se = new STBException("It's broken");
            se.initCause(ioe);
            throw se;
        }
    }

    public void fpTestMethodDeclaresThrownType(File f1, File f2) throws STBException, IOException {
        try {
            InputStream is = new FileInputStream(f1);
        } catch (IOException ioe) {
            throw new STBException();
        }

        try {
            InputStream is = new FileInputStream(f2);
        } catch (IOException ioe) {
            throw new STBException();
        }
    }

    public void fpTestDiffMessages(File f1, File f2) throws STBException {
        try {
            InputStream is = new FileInputStream(f1);
        } catch (IOException ioe) {
            throw new STBException("Couldn't open file 1");
        }

        try {
            InputStream is = new FileInputStream(f2);
        } catch (IOException ioe) {
            throw new STBException("Couldn't open file 2");
        }
    }

    public void fpTestDiffMessagesByAppending(File f1, File f2) throws STBException {
        try {
            InputStream is = new FileInputStream(f1);
        } catch (IOException ioe) {
            throw new STBException("Couldn't open file: " + f1);
        }

        try {
            InputStream is = new FileInputStream(f2);
        } catch (IOException ioe) {
            throw new STBException("Couldn't open file: " + f2);
        }
    }

    public int fpStackedAcrossCases(int choice, String val) throws STBException {
        switch (choice) {
        case 0:
            return Integer.parseInt(val);

        case 1:
            switch (val) {
            case "A":
                try {
                    return Integer.parseInt(val + "0");
                } catch (NumberFormatException e) {
                    throw new STBException("The lotto machine is broken");
                }

            case "B":
                try {
                    return Integer.parseInt(val + "00");
                } catch (NumberFormatException e) {
                    throw new STBException("The lotto machine is broken");
                }
            }
        }

        return 0;
    }

    static class STBException extends Exception {

        public STBException() {
        }

        public STBException(String message) {
            super(message);
        }
    }
}
