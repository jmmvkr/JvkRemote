package jvk.http;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.InvalidPropertiesFormatException;
import java.util.Properties;

import javax.swing.JFrame;

//import sun.misc.IOUtils;

public class JvkHttp {

	private static final String STR_HTTP_OK = "HTTP/1.0 200 OK\r\n";
	private static final String STR_REMOTE = "remoteURI";
	private static final String STR_LOCAL_PORT = "localPort";
	private static final String PROP_FILE = "properties.xml";
	private static final String MIME_FILE = "mime.xml";

	private static boolean bCanFixProp = true;
	private static boolean bRunning = false;
	private static boolean bAbort = false;
	private static String userDir = "";
	private static File userDirRt = new File(".");
	private static byte[] nothing = new byte[0];
	private static Properties p = new Properties();
	private static Properties mime = new Properties();
	private static Socket lastSck = null;

	private static void readWorkingDir() {
		userDir = System.getProperty("user.dir");
		userDirRt = new File(new File(userDir), "www");
		userDirRt.mkdirs();
		try {
			System.err.println(userDirRt.getCanonicalPath());
		} catch (IOException e) {
		}
	}

	private static byte[] readAllBytes(File f) {
		FileInputStream fin;
		byte[] byteRet = nothing;
		try {
			fin = new FileInputStream(f);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return nothing;
		}
		
		try {
			//byteRet = IOUtils.readFully(fin, (int) f.length(), true);
			byteRet = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(f.getPath()));
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		try {
			fin.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return byteRet;
	}

	private static void endWithMime(StringBuilder sb, String mime) {
		sb.append("Content-Type: ");
		sb.append(mime);
		sb.append("\r\n\r\n");
	}

	private static void writeBuffer(OutputStream out, StringBuilder sb) throws IOException {
		out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static void processSck(Socket sck) throws IOException {
		InputStream in = sck.getInputStream();
		InputStreamReader isr = new InputStreamReader(in);
		BufferedReader br = new BufferedReader(isr);

		String req = br.readLine();
		if(null == req) return;
		System.err.println(req);
		System.err.flush();

		OutputStream out = sck.getOutputStream();

		String line;
		while(null != (line = br.readLine()))
		{
			if(line.isEmpty()) break;
			// read header
			System.out.printf("%s%n", line);
		}
		System.out.flush();

		StringBuilder sb = new StringBuilder();
		
		int idxA = req.indexOf(" ");
		int idxB = req.lastIndexOf(" ");

		String pthFull = req.substring(idxA+2, idxB);
		String pth = pthFull.split("[?]")[0];
		if(pth.isEmpty()) pth = "index.html";
		File f = new File(userDirRt, pth);
		
		if(pth.equals("quit")) {
			bRunning = false;
			sb.append(STR_HTTP_OK);
			endWithMime(sb, "text/plain");
			sb.append("server quit");
			writeBuffer(out, sb);
		}
		else if(pth.equals("info")) {
			// write status + header + body (date)
			sb.append(STR_HTTP_OK);
			endWithMime(sb, "text/plain");
			String fmt = "%s: %s\n";
			sb.append(String.format(fmt, "webdir", userDirRt.getCanonicalPath()));
			sb.append(String.format(fmt, "remote", p.getProperty(STR_REMOTE)));
			sb.append(String.format(fmt, "date", new Date().toString()));
			writeBuffer(out, sb);
		}
		else if(pth.length() > 0 && f.isFile()) {
			// write header
			int lastDot = pth.lastIndexOf(".");
			String ext = pth.substring(lastDot);
			//System.err.println("ext="+ext);
			sb.append(STR_HTTP_OK);
			/*
			if(pth.endsWith(".html") || pth.endsWith(".htm")) {
				endWithMime(sb, "text/html");
			}
			else if(pth.endsWith(".xml")) {
				endWithMime(sb, "text/xml");
			}
			else if(pth.endsWith(".css")) {
				endWithMime(sb, "text/css");
			}
			else if(pth.endsWith(".js")) {
				endWithMime(sb, "text/javascript");
			}
			else if(pth.endsWith(".png")) {
				endWithMime(sb, "image/png");
			}
			*/
			String m = mime.getProperty(ext);
			if(null != m && (! m.isEmpty())) {
				endWithMime(sb, m);
			}
			else {
				endWithMime(sb, "application/octet-stream");
			}
			writeBuffer(out, sb);

			// write body (file)
			byte[] bArr= readAllBytes(f);
			out.write(bArr);
		} else {
			String host = p.getProperty(STR_REMOTE);
			try {
				delegateRequest(out, sb, host , pthFull);
			} catch (FileNotFoundException fex) {
				// write status + header + body (404)
				err(sb, "404 Not Found", "The requested URL was not found on this server.");
				writeBuffer(out, sb);
			} catch (IOException fex) {
				err(sb, "504 Gateway Timeout", "The server was acting as a gateway or proxy and did not receive a timely response from the upstream server.");
				writeBuffer(out, sb);
			}
		}
	
		out.flush();

		br.close();
		isr.close();
	}

	private static void delegateRequest(OutputStream out, StringBuilder sb, String host, String pthFull) throws IOException {
		URL u = new URL(host + pthFull);
		System.err.println(u.toString());
		System.err.flush();

		URLConnection conn = u.openConnection();
		conn.setReadTimeout(5000);
		String type = conn.getContentType();
		InputStream uin = conn.getInputStream();

		sb.append(STR_HTTP_OK);
		endWithMime(sb, conn.getContentType());
		writeBuffer(out, sb);
		sb.delete(0, sb.length());

		System.err.print("remote: ");
		System.err.println(type);
		byte[] bArr = getBytesFromInputStream(uin);
		//System.err.print(String.format("len=%d", bArr.length));
		//System.err.print(new String(bArr));
		//System.err.flush();
		out.write(bArr);
		uin.close();
		System.err.flush();
	}

	private static byte[] getBytesFromInputStream(InputStream is) throws IOException {
	    ByteArrayOutputStream os = new ByteArrayOutputStream(); 
	    byte[] buffer = new byte[4096];
	    for (int len = is.read(buffer); len != -1; len = is.read(buffer)) { 
	        os.write(buffer, 0, len);
	    }
	    return os.toByteArray();
	}

	private static void err(StringBuilder sb, String t, String msg) {
		System.err.println(t);
		System.err.flush();
		
		// status line
		sb.append("HTTP/1.0 ");
		sb.append(t);
		sb.append("\r\n");
		// header
		endWithMime(sb, "text/html");
		// body
		String txt = String.format("<html><head><title>%s</title></head><body><h1>%s</h1><p>%s</p></body></html>", t, t, msg);
		sb.append(txt);
	}

	private static boolean loadProp(Properties p, boolean bFix, String filePath) {
		try {
			p.loadFromXML(new FileInputStream(filePath));
			return true;
		} catch (InvalidPropertiesFormatException e) {
			e.printStackTrace();
		} catch (FileNotFoundException e) {
			if(! bFix) {
				e.printStackTrace();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return false;
	}

	private static JFrame openServerWindow(final ServerSocket svr) {
		JFrame jf = new JFrame(String.format("Jvk Remote (port:%s)", p.getProperty(STR_LOCAL_PORT)));
		jf.setSize(320, 60);
		jf.setLocation(20, 20);
		jf.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		jf.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent we) {
				bAbort = true;
				bRunning = false;
				try {
					svr.close();
				} catch (IOException e) {
				}
				try {
					Socket last = lastSck;
					if(null != last) {
						last.close();
					}
				} catch (IOException e) {
				}
			}
		});
		return jf;
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		try {
			if(! loadProp(p, bCanFixProp, PROP_FILE)) {
				if(bCanFixProp) {
					bCanFixProp = false;
					p.setProperty(STR_REMOTE, "https://www.google.com/?q=");
					p.setProperty(STR_LOCAL_PORT, "8888");
					p.storeToXML(new FileOutputStream(PROP_FILE), "");
					main(args);
				}
				return;
			}
			if(! loadProp(mime, true, MIME_FILE)) {
				mime.setProperty(".html", "text/html");
				mime.setProperty(".htm", "text/html");
				mime.setProperty(".css", "text/css");
				mime.setProperty(".js", "text/javascript");
				mime.setProperty(".png", "image/png");
				mime.setProperty(".jpg", "image/jpeg");
				mime.storeToXML(new FileOutputStream(MIME_FILE), "");
				if(! loadProp(mime, false, MIME_FILE)) {
					return;
				}
			}
			readWorkingDir();

			int svrPort;
			try {
				svrPort = Integer.parseInt(p.getProperty(STR_LOCAL_PORT));
			} catch (NumberFormatException e1) {
				svrPort = 8888;
			}
			ServerSocket svr = new ServerSocket(svrPort);
			//ServerSocket svr = new ServerSocket(8888);
			JFrame wnd = openServerWindow(svr);
			wnd.setVisible(true);
			bRunning = true;
			while(bRunning)
			{
				try {
					Socket sck = svr.accept();
					lastSck = sck;
					processSck(sck);
					sck.close();
				} catch (Exception e) {
					if(! bAbort) {
						e.printStackTrace();
					}
				}
			}
			svr.close();
			
			if(!bAbort) {
				wnd.dispose();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
