package com.acunetix;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import javax.net.ssl.SSLHandshakeException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;


public class BuildScanner extends hudson.tasks.Builder implements SimpleBuildStep {

    private final String apiUrl;
    private final String apiKey;
    private final String profile;
    private final String target;
    private String targetName;
    private final String repTemp;
    private String reportTemplateName;
    private final String threat;
    private final Boolean stopScan;

    @DataBoundConstructor
    public BuildScanner(String profile, String target, String repTemp, String threat, Boolean stopScan) {
        this.apiUrl = getDescriptor().getgApiUrl();
        this.apiKey = getDescriptor().getgApiKey();
        this.profile = profile;
        this.target = target;
        this.repTemp = repTemp;
        this.threat = threat;
        this.stopScan = stopScan;

        try {
            Engine aac = new Engine(this.apiUrl, this.apiKey);
            this.targetName = aac.getTargetName(this.target);
            this.reportTemplateName = aac.getReportTemplateName(this.repTemp);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private String getApiUrl() {
        return apiUrl;
    }

    private String getApiKey() {
        return apiKey;
    }

    public String getProfile() {
        return profile;
    }

    public String getTarget() {
        return target;
    }

    public String getRepTemp() {
        return repTemp;
    }

    public String getThreat() {
        return threat;
    }

    public Boolean getStopScan() {
        return stopScan;
    }

    private String getTargetName() {
        return targetName;
    }

    private String getReportTemplateName() {
        return reportTemplateName;
    }


    @Override
    public void perform(@Nonnull Run<?, ?> build, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws hudson.AbortException, InterruptedException {
        final String PROCESSING = "processing";
        final String COMPLETED = "completed";
        final String ABORTED = "aborted";
        final String SCHEDULED = "scheduled";
        final String QUEUED = "queued";
        final String NOREPORT = "no_report";
        final PrintStream listenerLogger = listener.getLogger();

        Engine engine = new Engine(this.apiUrl, this.apiKey);
        String scanId = null;
        Boolean scanAbortedExternally = false;
        Boolean scanAbortedByUser = false;
        String scanStatus = "";
        String scanThreat;
        Boolean started = false;
        Boolean bThreat = false;
        Boolean bScheduled = false;

        try {
            listenerLogger.println(SR.getString("starting.scan.on.target.0", getTargetName()));
            scanId = engine.startScan(profile, target, false);
            while (!scanStatus.equals(COMPLETED)) {
                if (scanStatus.equals(PROCESSING) && !started) {
                    started = true;
                    listenerLogger.println(SR.getString("scan.started"));
                }
                if (scanStatus.equals(SCHEDULED) && !bScheduled) {
                    bScheduled = true;
                    listenerLogger.println(SR.getString("the.scan.is.in.scheduled.state"));
                }
                if (scanStatus.equals(ABORTED)) {
                    scanAbortedExternally = true;
                    listenerLogger.println(SR.getString("aborting.the.build"));
                    throw new hudson.AbortException(SR.getString("scan.aborted.outside"));
                }

                scanThreat = engine.getScanThreat(scanId);
                if (engine.checkThreat(threat, scanThreat)) {
                    bThreat = true;
                    listenerLogger.println(SR.getString("scan.threat.0.1", Engine.getThreatName(scanThreat), this.getThreat()));
                    listenerLogger.println(SR.getString("aborting.the.build"));
                    throw new hudson.AbortException(SR.getString("scan.threat"));
                }
                Thread.sleep(1000);
                scanStatus = engine.getScanStatus(scanId);
            }
            listenerLogger.println(SR.getString("scan.completed"));
        } catch (InterruptedException e) {
            scanAbortedByUser = true;
            listenerLogger.println(SR.getString("aborting.the.build"));
            throw new hudson.AbortException(SR.getString("build.aborted"));
        } catch (hudson.AbortException e) {
            throw e;
        } catch (SSLHandshakeException e) {
            e.printStackTrace();
            throw new hudson.AbortException(SR.getString("certificate.to.the.java.ca.store"));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            if (!engine.checkScanExist(scanId)) {
                listenerLogger.println(SR.getString("aborting.the.build"));
                scanAbortedExternally = true;
                throw new hudson.AbortException(SR.getString("could.not.find.scan.with.scanid.0", scanId));
            }
        } catch (java.net.ConnectException e) {
            e.printStackTrace();
            listenerLogger.println(SR.getString("aborting.the.build"));
            scanAbortedExternally = true;
            throw new hudson.AbortException(SR.getString("could.not.connect.to.application.connection.refused"));
        } catch (Exception e) {
            e.printStackTrace();
            listenerLogger.println(e.getMessage());
        } finally {
            try {
                if (stopScan && scanId != null && !scanAbortedExternally && (bThreat || scanAbortedByUser) && !bScheduled) {
                    engine.stopScan(scanId);
                    try {
                        String status = "";
                        while (!status.equals(ABORTED) && !status.equals(COMPLETED)) {
                            Thread.sleep(1000);
                            status = engine.getScanStatus(scanId);
                        }
                        listenerLogger.println(SR.getString("the.scan.was.stopped"));
                    } catch (InterruptedException | IOException e) {
                        e.printStackTrace();
                        listenerLogger.println(e.getMessage());
                    }
                }
                if (!repTemp.equals(NOREPORT) && !scanAbortedByUser && !scanAbortedExternally) {
                    listenerLogger.println(SR.getString("generating.0.report", getReportTemplateName()));
                    Thread.sleep(1000);
                    String downloadLink = engine.generateReport(scanId, repTemp, "scans");
                    URL url = new URL(apiUrl);
                    engine.doDownload(url.getProtocol() + "://" + url.getAuthority() + downloadLink, workspace.getRemote(), Integer.toString(build.getNumber()));
                }
            } catch (InterruptedException | IOException e) {
                e.printStackTrace();
                listenerLogger.println(e.getMessage());
            }
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<hudson.tasks.Builder> {
        private String gApiUrl;
        private String gApiKey;

        public DescriptorImpl() {
            load();
        }

        public FormValidation doTestConnection(@QueryParameter("gApiUrl") final String ApiUrl,
                                               @QueryParameter("gApiKey") final String apiKey) {
            try {
                if (ApiUrl.length() == 0)
                    return FormValidation.error(SR.getString("please.set.the.api.url"));
                if (apiKey.length() == 0)
                    return FormValidation.error(SR.getString("please.set.the.api.key"));
                Engine apio = new Engine(ApiUrl, apiKey);
                int respCode = apio.doTestConnection(ApiUrl + "/me");

                if (respCode == 200) {
                    return FormValidation.ok(SR.getString("connected.successfully"));
                }
            } catch (SSLHandshakeException e) {
                e.printStackTrace();
                return FormValidation.error(SR.getString("certificate.to.the.java.ca.store"));
            } catch (IOException e) {
                e.printStackTrace();
                return FormValidation.error(e.getMessage());
            }
            return FormValidation.error(SR.getString("cannot.connect.to.application"));
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Acunetix";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            gApiUrl = formData.getString("gApiUrl");
            gApiKey = formData.getString("gApiKey");
            save();
            return super.configure(req, formData);
        }

        public String getgApiUrl() {
            return gApiUrl;
        }

        public String getgApiKey() {
            return gApiKey;
        }

        public ListBoxModel doFillProfileItems() throws IOException {
            ListBoxModel items = new ListBoxModel();
            Engine apio = new Engine(gApiUrl, gApiKey);
            JSONArray jsa = apio.getScanningProfiles();
            for (int i = 0; i < jsa.size(); i++) {
                JSONObject item = jsa.getJSONObject(i);
                String profile_name = item.getString("name");
                String profile_id = item.getString("profile_id");
                items.add(profile_name, profile_id);
            }
            return items;
        }

        public ListBoxModel doFillTargetItems() throws IOException {
            ListBoxModel items = new ListBoxModel();
            Engine apio = new Engine(gApiUrl, gApiKey);
            JSONArray jsa = apio.getTargets();
            for (int i = 0; i < jsa.size(); i++) {
                JSONObject item = jsa.getJSONObject(i);
                String mi = item.getString("manual_intervention");
                if (mi.equals("null") || mi.equals("false")) {
                    String address = item.getString("address");
                    String target_id = item.getString("target_id");
                    String description = item.getString("description");
                    String target_name = address;
                    if (description.length() > 0) {
                        if (description.length() > 100) {
                            description = description.substring(0, 100);
                        }
                        target_name += "  (" + description + ")";
                    }
                    items.add(target_name, target_id);
                }
            }
            return items;
        }

        public ListBoxModel doFillRepTempItems() throws IOException {
            ListBoxModel items = new ListBoxModel();
            Engine apio = new Engine(gApiUrl, gApiKey);
            JSONArray jsa = apio.getReportTemplates();
            items.add("Do not generate a report", "no_report");
            for (int i = 0; i < jsa.size(); i++) {
                JSONObject item = jsa.getJSONObject(i);
                String group = item.getString("group");
                if (group.equals("Standard Reports")) {
                    String reportTemplate_name = item.getString("name");
                    String template_id = item.getString("template_id");
                    if (!reportTemplate_name.equals("Scan Comparison")) {
                        items.add(reportTemplate_name, template_id);
                    }
                }
            }
            return items;
        }

        public ListBoxModel doFillThreatItems() throws IOException {
            ListBoxModel items = new ListBoxModel();
            items.add("Do not fail the build", "DoNotFail");
            items.add("High", "High");
            items.add("Medium or High", "Medium");
            items.add("Low, Medium or High", "Low");
            return items;
        }
    }
}
