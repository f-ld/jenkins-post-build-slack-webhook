package org.jenkinsci.plugins.postbuildslackwebhook;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.*;
import hudson.tasks.*;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import okhttp3.*;
import org.apache.commons.lang.ObjectUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;
import org.springframework.util.StringUtils;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;


public class SlackWebhookPublisher extends Recorder implements SimpleBuildStep {
    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String slackWebhook;
    private final String slackChannel;
    private final String slackUsername;
    private final String slackEmoji;
    private final String title;
    private final String link;
    private final String pretext;
    private final boolean pretextIncludesMarkdown;
    private final String text;
    private final boolean textIncludesMarkdown;
    private final String colorSuccess;
    private final String colorUnstable;
    private final String colorFailure;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public SlackWebhookPublisher(String slackWebhook, String slackChannel, String slackUsername, String slackEmoji, String title, String link, String pretext, boolean pretextIncludesMarkdown, String text, boolean textIncludesMarkdown, String colorSuccess, String colorUnstable, String colorFailure) {
        this.slackWebhook = slackWebhook;
        this.slackChannel = slackChannel;
        this.slackUsername = slackUsername;
        this.slackEmoji = slackEmoji;
        this.title = title;
        this.link = link;
        this.pretext = pretext;
        this.pretextIncludesMarkdown = pretextIncludesMarkdown;
        this.text = text;
        this.textIncludesMarkdown = textIncludesMarkdown;
        this.colorSuccess = colorSuccess;
        this.colorUnstable = colorUnstable;
        this.colorFailure = colorFailure;
    }

    public String getSlackWebhook() {
        return slackWebhook;
    }

    public String getSlackChannel() {
        return slackChannel;
    }

    public String getSlackUsername() {
        return slackUsername;
    }

    public String getSlackEmoji() {
        return slackEmoji;
    }

    public String getTitle() {
        return title;
    }

    public String getLink() {
        return link;
    }

    public String getPretext() {
        return pretext;
    }

    public boolean isPretextIncludesMarkdown() {
        return pretextIncludesMarkdown;
    }

    public String getText() {
        return text;
    }

    public boolean isTextIncludesMarkdown() {
        return textIncludesMarkdown;
    }

    public String getColorSuccess() {
        return colorSuccess;
    }

    public String getColorUnstable() {
        return colorUnstable;
    }

    public String getColorFailure() {
        return colorFailure;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public void perform(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener)
            throws IOException, InterruptedException{
        // Get the configuration for important keys (between project config and default config)
        String webhook = this.getSlackWebhook();
        String channel = this.getSlackChannel();
        String username = this.getSlackUsername();
        String emoji = this.getSlackEmoji();
        String color = this.getColorUnstable();
        try {
            if ((build != null) && (build.getResult() != null)) {
                if (build.getResult().equals(Result.SUCCESS)) {
                    color = this.getColorSuccess();
                } else if (build.getResult().equals(Result.FAILURE)) {
                    color = this.getColorFailure();
                }
            }
        } catch (NullPointerException npe) {}
        if (webhook == null) {
            webhook = getDescriptor().getSlackDefaultWebhook();
        }
        if (channel == null) {
            channel = getDescriptor().getSlackDefaultChannel();
        }
        if (username == null) {
            username = getDescriptor().getSlackDefaultUsername();
        }
        if (emoji == null) {
            emoji = getDescriptor().getSlackDefaultEmoji();
        }

        // Prepare for the POST request to Slack
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .build();

        final EnvVars env = build.getEnvironment(listener);
//        for (Iterator<ParametersAction> it = build.getActions(ParametersAction.class).iterator(); it.hasNext();) {
//            for (Iterator<ParameterValue> paramIt = it.next().getParameters().iterator(); paramIt.hasNext();) {
//                ParameterValue value = paramIt.next();
//                env.put(value.getName(), value.getValue().toString());
//            }
//        }
        env.put("BUILD_STATUS", build.getResult().toString());

        // Build the json body of the slack request
        JSONObject jsonObject = new JSONObject();
        if (StringUtils.hasText(username))
            jsonObject.put("username", env.expand(username));
        if (StringUtils.hasText(channel))
            jsonObject.put("channel", env.expand(channel));
        if (StringUtils.hasText(emoji))
            jsonObject.put("emoji", env.expand(emoji));
        JSONArray attachments = new JSONArray();
        JSONObject message = new JSONObject();
        if (StringUtils.hasText(this.getTitle()))
            message.put("title", env.expand(this.getTitle()));
        if (StringUtils.hasText(color))
            message.put("color", env.expand(color));
        if (StringUtils.hasText(this.getText()))
            message.put("text", env.expand(this.getText()));
        if (StringUtils.hasText(this.getPretext()))
            message.put("pretext", env.expand(this.getPretext()));
        if (StringUtils.hasText(this.getLink()))
            message.put("title_link", env.expand(this.getLink()));
        if (this.isTextIncludesMarkdown() || this.isPretextIncludesMarkdown()) {
            JSONArray markdown = new JSONArray();
            if (this.isTextIncludesMarkdown()) {
                markdown.add("text");
            }
            if (this.isPretextIncludesMarkdown()) {
                markdown.add("pretext");
            }
            message.put("mrkdwn_in", markdown);
        }
        attachments.add(message);
        jsonObject.put("attachments", attachments);

        // Prepare the POST request to slack
        RequestBody requestBody = RequestBody.create(JSON, jsonObject.toString());
        Request request = new Request.Builder()
                .url(env.expand(webhook))
                .post(requestBody)
                .build();

        // Send the request and print the result of the action
        listener.getLogger().println("Sending request to Slack: payload="+jsonObject.toString());

        long start = System.nanoTime();
        try {
            Response response = client.newCall(request).execute();
            long time = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            listener.getLogger().println(String.format("Slack response: %s %s (%sms) [%s]",
                                                       response.code(), response.message(), time,
                                                       response.body().string()));
        } catch (IOException ioe) {
            long time = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            listener.getLogger().println(String.format("Error sending request to Slack: %s (%sms)", ioe.toString(), time));
        }
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link SlackWebhookPublisher}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See <tt>src/main/resources/hudson/plugins/hello_world/SlackWebhookPublisher/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private String slackDefaultWebhook;
        private String slackDefaultChannel;
        private String slackDefaultUsername;
        private String slackDefaultEmoji;

        public DescriptorImpl() {
            load();
        }

        private FormValidation isValidColor(String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a color");
            if (!(value.equals("good") || value.equals("warning") || value.equals("danger") || value.startsWith("#")))
                return FormValidation.warning("The color you entered looks invalid");
            return FormValidation.ok();
        }

        public FormValidation doCheckcolorSuccess(@QueryParameter String value)
                throws IOException, ServletException {
            return isValidColor(value);
        }

        public FormValidation doCheckcolorUnstable(@QueryParameter String value)
                throws IOException, ServletException {
            return isValidColor(value);
        }

        public FormValidation doCheckcolorFailed(@QueryParameter String value)
                throws IOException, ServletException {
            return isValidColor(value);
        }

        public FormValidation doCheckLink(@QueryParameter String value)
                throws IOException, ServletException {
            if (!value.startsWith("http"))
                return FormValidation.warning("Typical links start with https:// (or http://)");
            return FormValidation.ok();
        }

        public FormValidation doCheckSlackWebhook(@QueryParameter String value)
                throws IOException, ServletException {
            if ((value.length() == 0) && !StringUtils.hasText(getSlackDefaultWebhook()))
                return FormValidation.error("Please set a webhook url");
            if (!value.startsWith("https://hooks.slack.com/services/"))
                return FormValidation.warning("Is this a valid slack webhook URL?");
            return FormValidation.ok();
        }
        public FormValidation doCheckSlackDefaultWebHook(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.warning("Please set a default webhook url");
            if (!value.startsWith("https://hooks.slack.com/services/"))
                return FormValidation.warning("Is this a valid slack webhook URL?");
            return FormValidation.ok();
        }

        public FormValidation doCheckSlackDefaultChannel(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.warning("Please set a default channel");
            return FormValidation.ok();
        }

        public FormValidation doCheckSlackDefaultUsername(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.warning("Please set a default username");
            return FormValidation.ok();
        }

        public FormValidation doCheckSlackDefaultEmoji(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a default emoji");
            if (!value.startsWith(":") || !value.endsWith(":"))
                return FormValidation.warning("A valid emoji is a string starting and ending with ':', like \":robot_face:\"");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Slack Webhook notification";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
            slackDefaultWebhook = formData.getString("slackDefaultWebhook");
            slackDefaultChannel = formData.getString("slackDefaultChannel");
            slackDefaultUsername = formData.getString("slackDefaultUsername");
            slackDefaultEmoji = formData.getString("slackDefaultEmoji");
            save();
            return super.configure(req,formData);
        }

        public String getSlackDefaultWebhook() {
            return slackDefaultWebhook;
        }

        public String getSlackDefaultChannel() {
            return slackDefaultChannel;
        }

        public String getSlackDefaultUsername() {
            return slackDefaultUsername;
        }

        public String getSlackDefaultEmoji() {
            return slackDefaultEmoji;
        }
    }
}

