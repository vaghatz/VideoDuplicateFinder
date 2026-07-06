import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class VideoDuplicateFinder extends JFrame {

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
        ".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv", ".webm",
        ".m4v", ".mpg", ".mpeg", ".3gp", ".ts", ".vob", ".ogv"
    );

    private final DefaultListModel<String> folderListModel = new DefaultListModel<>();
    private final JList<String> folderList = new JList<>(folderListModel);
    private final DefaultTableModel tableModel;
    private final JTable resultsTable;
    private final JLabel statusLabel = new JLabel("Add folders and click 'Find Duplicates'");
    private final JProgressBar progressBar = new JProgressBar();
    private final JButton scanButton = new JButton("Find Duplicates");
    private final JButton addFolderButton = new JButton("Add Folder");
    private final JButton removeFolderButton = new JButton("Remove Selected");
    private final JCheckBox recurseCheckbox = new JCheckBox("Include subfolders", true);
    private final JComboBox<String> matchModeCombo = new JComboBox<>(new String[]{
        "Match by size on disk",
        "Match by duration (\u00b11s)",
        "Match by duration (exact)",
        "Duration + video hash (verified)",
        "Match by filename (exact)",
        "Match by filename (approximate)"
    });
    private final JTextField ffprobePathField = new JTextField("ffprobe", 18);
    private final JButton browseFfprobeBtn = new JButton("Browse...");
    private final JTextField ffmpegPathField = new JTextField("ffmpeg", 18);
    private final JButton browseFfmpegBtn = new JButton("Browse...");
    private final JSpinner similarityThresholdSpinner = new JSpinner(new SpinnerNumberModel(80, 50, 100, 1));
    private final Map<String, String> lastSimilarityNotes = new HashMap<>();

    private static final int HASH_SIZE = 8;
    private static final int DCT_SIZE = HASH_SIZE * 4;
    private static final int HASH_BITS = HASH_SIZE * HASH_SIZE;
    private static final double[] SAMPLE_FRACTIONS = {0.10, 0.30, 0.50, 0.70, 0.90};
    private static final File FOLDERS_FILE = new File("saved_folders.txt");

    public VideoDuplicateFinder() {
        super("Video Duplicate Finder");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(950, 650);
        setLocationRelativeTo(null);

        String[] columns = {"Delete?", "Group", "File Name", "Duration", "Size (MB)", "Similarity", "Full Path"};
        tableModel = new DefaultTableModel(columns, 0) {
            public boolean isCellEditable(int r, int c) { return c == 0; }
            public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : Object.class; }
        };
        resultsTable = new JTable(tableModel);
        resultsTable.setRowHeight(24);
        resultsTable.getColumnModel().getColumn(0).setMaxWidth(55);
        resultsTable.getColumnModel().getColumn(0).setMinWidth(55);
        resultsTable.getColumnModel().getColumn(1).setMaxWidth(60);
        resultsTable.getColumnModel().getColumn(3).setMaxWidth(90);
        resultsTable.getColumnModel().getColumn(4).setMaxWidth(100);
        resultsTable.getColumnModel().getColumn(5).setMaxWidth(90);
        resultsTable.setAutoCreateRowSorter(true);

        resultsTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private final Color COLOR_A = new Color(232, 245, 253);
            private final Color COLOR_B = UIManager.getColor("Table.background");
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (!sel) {
                    Object grp = t.getValueAt(row, 1);
                    int g = grp instanceof Integer ? (int) grp : 0;
                    c.setBackground(g % 2 == 0 ? COLOR_A : COLOR_B);
                }
                return c;
            }
        });

        JPanel folderPanel = new JPanel(new BorderLayout(5, 5));
        folderPanel.setBorder(BorderFactory.createTitledBorder("Selected Folders"));
        folderPanel.setPreferredSize(new Dimension(300, 0));

        JPanel folderButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        folderButtons.add(addFolderButton);
        folderButtons.add(removeFolderButton);

        folderPanel.add(new JScrollPane(folderList), BorderLayout.CENTER);
        folderPanel.add(folderButtons, BorderLayout.SOUTH);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.add(recurseCheckbox);
        controlPanel.add(Box.createHorizontalStrut(15));
        controlPanel.add(new JLabel("Match by:"));
        controlPanel.add(matchModeCombo);
        controlPanel.add(Box.createHorizontalStrut(15));
        controlPanel.add(scanButton);

        JPanel ffprobePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        ffprobePanel.add(new JLabel("FFprobe path:"));
        ffprobePanel.add(ffprobePathField);
        ffprobePanel.add(browseFfprobeBtn);
        ffprobePanel.setVisible(false);

        JPanel hashPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        hashPanel.add(new JLabel("FFmpeg path:"));
        hashPanel.add(ffmpegPathField);
        hashPanel.add(browseFfmpegBtn);
        hashPanel.add(Box.createHorizontalStrut(15));
        hashPanel.add(new JLabel("Min similarity % to confirm:"));
        hashPanel.add(similarityThresholdSpinner);
        hashPanel.setVisible(false);

        matchModeCombo.addActionListener(e -> updateOptionPanels(ffprobePanel, hashPanel));
        updateOptionPanels(ffprobePanel, hashPanel);

        browseFfprobeBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                ffprobePathField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        browseFfmpegBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                ffmpegPathField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        JPanel controlWrapper = new JPanel();
        controlWrapper.setLayout(new BoxLayout(controlWrapper, BoxLayout.Y_AXIS));
        controlWrapper.add(controlPanel);
        controlWrapper.add(ffprobePanel);
        controlWrapper.add(hashPanel);

        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        progressBar.setVisible(false);
        bottomPanel.add(statusLabel, BorderLayout.CENTER);
        bottomPanel.add(progressBar, BorderLayout.SOUTH);
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        JPanel resultsPanel = new JPanel(new BorderLayout(5, 5));
        resultsPanel.setBorder(BorderFactory.createTitledBorder("Duplicate Groups"));
        resultsPanel.add(new JScrollPane(resultsTable), BorderLayout.CENTER);

        JButton selectAllBtn = new JButton("Select All");
        JButton deselectAllBtn = new JButton("Deselect All");
        JButton deleteBtn = new JButton("Delete Selected Files");
        deleteBtn.setForeground(Color.RED);

        JPanel resultsButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        resultsButtons.add(selectAllBtn);
        resultsButtons.add(deselectAllBtn);
        resultsButtons.add(Box.createHorizontalStrut(20));
        resultsButtons.add(deleteBtn);
        resultsPanel.add(resultsButtons, BorderLayout.SOUTH);

        selectAllBtn.addActionListener(e -> {
            for (int i = 0; i < tableModel.getRowCount(); i++) tableModel.setValueAt(true, i, 0);
        });
        deselectAllBtn.addActionListener(e -> {
            for (int i = 0; i < tableModel.getRowCount(); i++) tableModel.setValueAt(false, i, 0);
        });
        deleteBtn.addActionListener(e -> deleteSelectedFiles());

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(folderPanel, BorderLayout.WEST);
        topPanel.add(controlWrapper, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, resultsPanel);
        split.setDividerLocation(220);
        split.setResizeWeight(0.3);

        add(split, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        addFolderButton.addActionListener(e -> addFolder());
        removeFolderButton.addActionListener(e -> removeFolder());
        scanButton.addActionListener(e -> startScan());

        resultsTable.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = resultsTable.getSelectedRow();
                    if (row >= 0) {
                        String path = (String) resultsTable.getValueAt(row, 6);
                        try {
                            Desktop.getDesktop().open(new File(path));
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(null, "Cannot open file: " + ex.getMessage());
                        }
                    }
                }
            }
        });

        loadFolders();
    }

    private void addFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File f : chooser.getSelectedFiles()) {
                String path = f.getAbsolutePath();
                if (!folderListModel.contains(path)) folderListModel.addElement(path);
            }
            saveFolders();
        }
    }

    private void removeFolder() {
        int idx = folderList.getSelectedIndex();
        if (idx >= 0) {
            folderListModel.remove(idx);
            saveFolders();
        }
    }

    private void loadFolders() {
        if (!FOLDERS_FILE.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(FOLDERS_FILE))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !folderListModel.contains(line)) {
                    folderListModel.addElement(line);
                }
            }
        } catch (IOException ignored) {}
    }

    private void saveFolders() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(FOLDERS_FILE))) {
            for (int i = 0; i < folderListModel.size(); i++) {
                pw.println(folderListModel.get(i));
            }
        } catch (IOException ignored) {}
    }

    private void updateOptionPanels(JPanel ffprobePanel, JPanel hashPanel) {
        String selected = (String) matchModeCombo.getSelectedItem();
        boolean needFfprobe = selected.contains("duration") || selected.contains("video hash");
        boolean needHash = selected.contains("video hash");
        ffprobePanel.setVisible(needFfprobe);
        hashPanel.setVisible(needHash);
    }

    private String getSelectedMode() {
        String selected = (String) matchModeCombo.getSelectedItem();
        if (selected == null) return "size";
        if (selected.contains("video hash")) return "length_hash";
        if (selected.equals("Match by duration (\u00b11s)")) return "length_approx";
        if (selected.equals("Match by duration (exact)")) return "length_exact";
        if (selected.equals("Match by filename (exact)")) return "name";
        if (selected.equals("Match by filename (approximate)")) return "name_approx";
        return "size";
    }

    private void startScan() {
        if (folderListModel.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please add at least one folder.");
            return;
        }
        List<String> folders = Collections.list(folderListModel.elements());
        boolean recurse = recurseCheckbox.isSelected();

        scanButton.setEnabled(false);
        progressBar.setIndeterminate(true);
        progressBar.setVisible(true);
        statusLabel.setText("Scanning...");
        tableModel.setRowCount(0);

        String mode = getSelectedMode();
        String ffprobePath = ffprobePathField.getText().trim();
        String ffmpegPath = ffmpegPathField.getText().trim();
        int minSimilarityPercent = (Integer) similarityThresholdSpinner.getValue();
        double maxHashDistance = HASH_BITS * (100 - minSimilarityPercent) / 100.0;

        if (mode.equals("length_hash") && !isExecutableAvailable(ffmpegPath)) {
            JOptionPane.showMessageDialog(this,
                "Could not run ffmpeg at \"" + ffmpegPath + "\".\nPlease check the FFmpeg path and try again.",
                "FFmpeg not found", JOptionPane.ERROR_MESSAGE);
            scanButton.setEnabled(true);
            progressBar.setVisible(false);
            statusLabel.setText("Add folders and click 'Find Duplicates'");
            return;
        }
        lastSimilarityNotes.clear();

        new SwingWorker<List<List<File>>, String>() {
            protected List<List<File>> doInBackground() {
                List<File> allFiles = new ArrayList<>();
                for (String folder : folders) {
                    collectFiles(new File(folder), allFiles, recurse);
                }

                if (mode.equals("length_approx")) {
                    return groupByDuration(allFiles, ffprobePath, 1.0);
                } else if (mode.equals("length_exact")) {
                    return groupByDuration(allFiles, ffprobePath, 0.0);
                } else if (mode.equals("length_hash")) {
                    return groupByDurationThenHash(allFiles, ffprobePath, ffmpegPath, 1.0, maxHashDistance);
                } else if (mode.equals("name_approx")) {
                    return groupByApproximateName(allFiles);
                } else {
                    return groupByKey(allFiles, mode);
                }
            }

            private void collectFiles(File dir, List<File> out, boolean recurse) {
                File[] files = dir.listFiles();
                if (files == null) return;
                for (File f : files) {
                    if (f.isDirectory() && recurse) {
                        collectFiles(f, out, true);
                    } else if (f.isFile() && isVideo(f.getName())) {
                        out.add(f);
                    }
                }
            }

            private List<List<File>> groupByKey(List<File> files, String mode) {
                Map<String, List<File>> map = new HashMap<>();
                for (File f : files) {
                    publish(f.getName());
                    String key = mode.equals("name") ? f.getName().toLowerCase()
                                                     : String.valueOf(f.length());
                    map.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
                }
                map.entrySet().removeIf(e -> e.getValue().size() < 2);
                return new ArrayList<>(map.values());
            }

            private List<List<File>> groupByDuration(List<File> files, String ffprobe, double toleranceSec) {
                List<double[]> durationsAndIndices = new ArrayList<>();
                List<File> validFiles = new ArrayList<>();
                int total = files.size();
                for (int i = 0; i < total; i++) {
                    File f = files.get(i);
                    publish(String.format("(%d/%d) Probing: %s", i + 1, total, f.getName()));
                    double dur = getVideoDuration(f, ffprobe);
                    if (dur >= 0) {
                        durationsAndIndices.add(new double[]{dur});
                        validFiles.add(f);
                    }
                }

                if (toleranceSec <= 0) {
                    Map<Long, List<File>> map = new HashMap<>();
                    for (int i = 0; i < validFiles.size(); i++) {
                        long key = Math.round(durationsAndIndices.get(i)[0]);
                        map.computeIfAbsent(key, k -> new ArrayList<>()).add(validFiles.get(i));
                    }
                    map.entrySet().removeIf(e -> e.getValue().size() < 2);
                    return new ArrayList<>(map.values());
                }

                int n = validFiles.size();
                Integer[] indices = new Integer[n];
                for (int i = 0; i < n; i++) indices[i] = i;
                Arrays.sort(indices, (a, b) -> Double.compare(
                    durationsAndIndices.get(a)[0], durationsAndIndices.get(b)[0]));

                boolean[] used = new boolean[n];
                List<List<File>> groups = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    int si = indices[i];
                    if (used[si]) continue;
                    double baseDur = durationsAndIndices.get(si)[0];
                    List<File> group = new ArrayList<>();
                    group.add(validFiles.get(si));
                    used[si] = true;
                    for (int j = i + 1; j < n; j++) {
                        int sj = indices[j];
                        if (used[sj]) continue;
                        double d = durationsAndIndices.get(sj)[0];
                        if (d - baseDur > toleranceSec) break;
                        group.add(validFiles.get(sj));
                        used[sj] = true;
                    }
                    if (group.size() >= 2) groups.add(group);
                }
                return groups;
            }

            private List<List<File>> groupByDurationThenHash(List<File> files, String ffprobe, String ffmpeg,
                    double durationToleranceSec, double maxHashDistance) {
                List<List<File>> durationGroups = groupByDuration(files, ffprobe, durationToleranceSec);
                List<List<File>> confirmed = new ArrayList<>();
                int groupNum = 0;
                int totalGroups = durationGroups.size();

                for (List<File> candidates : durationGroups) {
                    groupNum++;
                    publish(String.format("Verifying group %d/%d (%d files, same duration) by video hash...",
                        groupNum, totalGroups, candidates.size()));

                    Map<File, long[]> fingerprints = new LinkedHashMap<>();
                    for (File f : candidates) {
                        long[] fp = computeVideoFingerprint(f, ffmpeg, ffprobe);
                        if (fp != null) fingerprints.put(f, fp);
                    }
                    List<File> valid = new ArrayList<>(fingerprints.keySet());
                    int n = valid.size();
                    if (n < 2) continue;

                    double[][] dist = new double[n][n];
                    for (int i = 0; i < n; i++) {
                        for (int j = i + 1; j < n; j++) {
                            double d = averageHammingDistance(fingerprints.get(valid.get(i)), fingerprints.get(valid.get(j)));
                            dist[i][j] = d;
                            dist[j][i] = d;
                        }
                    }

                    int[] parent = new int[n];
                    for (int i = 0; i < n; i++) parent[i] = i;
                    for (int i = 0; i < n; i++) {
                        for (int j = i + 1; j < n; j++) {
                            if (dist[i][j] <= maxHashDistance) unionSets(parent, i, j);
                        }
                    }
                    Map<Integer, List<Integer>> clusters = new LinkedHashMap<>();
                    for (int i = 0; i < n; i++) {
                        clusters.computeIfAbsent(findSet(parent, i), k -> new ArrayList<>()).add(i);
                    }

                    for (List<Integer> cluster : clusters.values()) {
                        if (cluster.size() < 2) continue;
                        List<File> group = new ArrayList<>();
                        for (int idx : cluster) {
                            double sum = 0;
                            int cnt = 0;
                            for (int other : cluster) {
                                if (other == idx) continue;
                                sum += dist[idx][other];
                                cnt++;
                            }
                            double avgDist = cnt > 0 ? sum / cnt : 0;
                            double similarityPct = 100.0 * (1.0 - avgDist / HASH_BITS);
                            File f = valid.get(idx);
                            lastSimilarityNotes.put(f.getAbsolutePath(), String.format("%.1f%%", similarityPct));
                            group.add(f);
                        }
                        confirmed.add(group);
                    }
                }
                return confirmed;
            }

            private long[] computeVideoFingerprint(File file, String ffmpegPath, String ffprobePath) {
                double duration = getVideoDuration(file, ffprobePath);
                if (duration <= 0) return null;
                long[] hashes = new long[SAMPLE_FRACTIONS.length];
                for (int i = 0; i < SAMPLE_FRACTIONS.length; i++) {
                    double ts = duration * SAMPLE_FRACTIONS[i];
                    publish(String.format("Hashing %s @ %.0f%%...", file.getName(), SAMPLE_FRACTIONS[i] * 100));
                    BufferedImage frame = extractFrame(file, ffmpegPath, ts);
                    if (frame == null) return null;
                    hashes[i] = perceptualHash(frame);
                }
                return hashes;
            }

            private List<List<File>> groupByApproximateName(List<File> files) {
                List<String> normalized = new ArrayList<>();
                for (File f : files) {
                    publish(f.getName());
                    String name = f.getName();
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) name = name.substring(0, dot);
                    name = name.toLowerCase().replaceAll("[^a-z0-9]", "");
                    normalized.add(name);
                }
                Map<String, List<File>> map = new HashMap<>();
                for (int i = 0; i < files.size(); i++) {
                    map.computeIfAbsent(normalized.get(i), k -> new ArrayList<>()).add(files.get(i));
                }
                map.entrySet().removeIf(e -> e.getValue().size() < 2);
                return new ArrayList<>(map.values());
            }

            protected void process(List<String> chunks) {
                if (!chunks.isEmpty())
                    statusLabel.setText(chunks.get(chunks.size() - 1));
            }

            protected void done() {
                try {
                    List<List<File>> groups = get();
                    groups.sort((a, b) -> Integer.compare(b.size(), a.size()));
                    int group = 1;
                    int totalDupes = 0;
                    for (List<File> grp : groups) {
                        for (File f : grp) {
                            double mb = f.length() / (1024.0 * 1024.0);
                            String sizeStr = String.format("%.2f", mb);
                            String durStr = formatDuration(getVideoDuration(f, ffprobePath));
                            String simStr = lastSimilarityNotes.getOrDefault(f.getAbsolutePath(), "-");
                            tableModel.addRow(new Object[]{
                                false, group, f.getName(), durStr, sizeStr, simStr, f.getAbsolutePath()
                            });
                            totalDupes++;
                        }
                        group++;
                    }
                    String modeLabel = mode.equals("name") ? "filename (exact)" :
                                       mode.equals("name_approx") ? "filename (approximate)" :
                                       mode.equals("length_approx") ? "duration (\u00b11s via ffprobe)" :
                                       mode.equals("length_exact") ? "duration (exact, via ffprobe)" :
                                       mode.equals("length_hash") ? "duration + video hash (verified)" :
                                       "exact file size";
                    statusLabel.setText(String.format("Done \u2014 %d duplicate groups, %d files (matched by %s).",
                        groups.size(), totalDupes, modeLabel));
                } catch (Exception ex) {
                    statusLabel.setText("Error: " + ex.getMessage());
                }
                progressBar.setVisible(false);
                scanButton.setEnabled(true);
            }
        }.execute();
    }

    private void deleteSelectedFiles() {
        List<Integer> rowsToDelete = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, 0))) {
                rowsToDelete.add(i);
            }
        }
        if (rowsToDelete.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No files selected for deletion.");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
            "Are you sure you want to permanently delete " + rowsToDelete.size() + " file(s)?\nThis cannot be undone.",
            "Confirm Deletion", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        int deleted = 0, failed = 0;
        for (int i = rowsToDelete.size() - 1; i >= 0; i--) {
            int row = rowsToDelete.get(i);
            String path = (String) tableModel.getValueAt(row, 6);
            File file = new File(path);
            if (file.delete()) {
                tableModel.removeRow(row);
                deleted++;
            } else {
                failed++;
            }
        }
        String msg = deleted + " file(s) deleted.";
        if (failed > 0) msg += " " + failed + " file(s) could not be deleted.";
        statusLabel.setText(msg);
        JOptionPane.showMessageDialog(this, msg);
    }

    private static boolean isVideo(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return VIDEO_EXTENSIONS.contains(name.substring(dot).toLowerCase());
    }

    private static String formatDuration(double seconds) {
        if (seconds < 0) return "N/A";
        int total = (int) Math.round(seconds);
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    private static double getVideoDuration(File file, String ffprobePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                ffprobePath,
                "-v", "quiet",
                "-print_format", "json",
                "-show_format",
                file.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            int exitCode = proc.waitFor();
            if (exitCode != 0) return -1.0;

            String json = sb.toString();
            String marker = "\"duration\"";
            int idx = json.indexOf(marker);
            if (idx < 0) return -1.0;
            int colon = json.indexOf(':', idx + marker.length());
            int quote1 = json.indexOf('"', colon + 1);
            int quote2 = json.indexOf('"', quote1 + 1);
            if (quote1 < 0 || quote2 < 0) return -1.0;
            return Double.parseDouble(json.substring(quote1 + 1, quote2));
        } catch (Exception e) {
            return -1.0;
        }
    }

    private static boolean isExecutableAvailable(String path) {
        try {
            Process p = new ProcessBuilder(path, "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    private static BufferedImage extractFrame(File file, String ffmpegPath, double timestampSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-ss", String.format(Locale.US, "%.3f", timestampSeconds),
                "-i", file.getAbsolutePath(),
                "-frames:v", "1",
                "-f", "image2pipe",
                "-vcodec", "png",
                "-loglevel", "error",
                "-"
            );
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            byte[] pngBytes;
            try (InputStream in = proc.getInputStream()) {
                pngBytes = in.readAllBytes();
            }
            boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return null;
            }
            if (pngBytes.length == 0) return null;
            return ImageIO.read(new ByteArrayInputStream(pngBytes));
        } catch (Exception e) {
            return null;
        }
    }

    private static long perceptualHash(BufferedImage img) {
        double[][] pixels = toGrayscaleResized(img, DCT_SIZE, DCT_SIZE);
        double[][] dct = dct2D(pixels);

        double[] low = new double[HASH_BITS];
        int k = 0;
        for (int y = 0; y < HASH_SIZE; y++) {
            for (int x = 0; x < HASH_SIZE; x++) {
                low[k++] = dct[y][x];
            }
        }
        double[] sorted = low.clone();
        Arrays.sort(sorted);
        double median = (sorted[low.length / 2 - 1] + sorted[low.length / 2]) / 2.0;

        long hash = 0L;
        for (int i = 0; i < low.length; i++) {
            if (low[i] > median) hash |= (1L << i);
        }
        return hash;
    }

    private static double[][] toGrayscaleResized(BufferedImage img, int w, int h) {
        Image scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2 = buffered.createGraphics();
        g2.drawImage(scaled, 0, 0, null);
        g2.dispose();
        double[][] out = new double[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y][x] = buffered.getRaster().getSample(x, y, 0);
            }
        }
        return out;
    }

    private static double[][] dct2D(double[][] input) {
        int rows = input.length;
        int cols = input[0].length;

        double[][] temp = new double[rows][cols];
        for (int c = 0; c < cols; c++) {
            double[] col = new double[rows];
            for (int r = 0; r < rows; r++) col[r] = input[r][c];
            double[] dctCol = dct1D(col);
            for (int r = 0; r < rows; r++) temp[r][c] = dctCol[r];
        }

        double[][] output = new double[rows][cols];
        for (int r = 0; r < rows; r++) {
            output[r] = dct1D(temp[r]);
        }
        return output;
    }

    private static double[] dct1D(double[] x) {
        int n = x.length;
        double[] out = new double[n];
        for (int kFreq = 0; kFreq < n; kFreq++) {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += x[i] * Math.cos(Math.PI / n * (i + 0.5) * kFreq);
            }
            out[kFreq] = 2 * sum;
        }
        return out;
    }

    private static double averageHammingDistance(long[] a, long[] b) {
        int n = Math.min(a.length, b.length);
        if (n == 0) return HASH_BITS;
        int total = 0;
        for (int i = 0; i < n; i++) {
            total += Long.bitCount(a[i] ^ b[i]);
        }
        return (double) total / n;
    }

    private static int findSet(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void unionSets(int[] parent, int a, int b) {
        int ra = findSet(parent, a);
        int rb = findSet(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new VideoDuplicateFinder().setVisible(true);
        });
    }
}
