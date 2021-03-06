# Title     : TODO
# Objective : TODO
# Created by: mandar
# Created on: 18/09/2018

#/usr/bin/Rscript
library(ggplot2)
library(latex2exp)
args <- commandArgs(trailingOnly = TRUE)
direc <- args[1]
lossFlag <- args[2]
setwd(direc)

palette2 <- c("firebrick3", "#000000")
palette3 <- c("#CC0C0099", "#5C88DA99")
posterior_samples <- read.csv("posterior_samples.csv", header = TRUE)
prior_samples <- read.csv("prior_samples.csv", header = TRUE)

posterior_samples$sample <- rep("posterior", nrow(posterior_samples))
prior_samples$sample <- rep("prior", nrow(prior_samples))

samples <- rbind(prior_samples, posterior_samples)
colnames(samples) <- colnames(posterior_samples)
samples$sample <- as.factor(samples$sample)

psd_data <- read.csv("bulk_data.csv")
colnames(psd_data) <- c("l", "t", "psd")
colocation_points <- read.csv("colocation_points.csv")
colocation_points$psd <- rep(NA, nrow(colocation_points))
colnames(colocation_points) <- c("l","t", "psd")


ggplot(psd_data, aes(x=t, y=l)) +
  geom_point(aes(color=log10(psd)), size = 1.5) +
  scale_color_viridis_c(name = TeX('$\\log_{10} \\left( \\frac{f(L^{*}, t)}{f_{min}} \\right) $')) +
  geom_point(data = colocation_points, aes(x=t, y=l), shape=4, size=2.5, color="red") +
  theme_gray(base_size = 20) +
  ylab(TeX('$L^{*}$')) +
  xlab(TeX('$t$')) +
  theme(legend.position="top", legend.direction = "horizontal")

ggsave("data_and_design_points_van_allen.png")
if (lossFlag == "loss") {
  qplot(
    prior_samples$"lambda_beta", prior_samples$"lambda_b",
    xlab = expression(beta), ylab = expression(b))

  ggsave("van_allen_scatter_beta_b_prior.png")

  qplot(
    posterior_samples$"lambda_beta", posterior_samples$"lambda_b",
    xlab = expression(beta), ylab = expression(b))
  ggsave("van_allen_scatter_lambda_beta_b_posterior.png")

  qplot(
    prior_samples$"lambda_beta", prior_samples$"lambda_alpha",
    xlab = expression(beta), ylab = expression(alpha))
  ggsave("van_allen_scatter_lambda_beta_alpha_prior.png")

  qplot(
    posterior_samples$"lambda_beta", posterior_samples$"lambda_alpha",
    xlab = expression(beta), ylab = expression(alpha))
  ggsave("van_allen_scatter_lambda_beta_alpha_posterior.png")


  ggplot(samples, aes(x = lambda_beta, y = lambda_b)) +
    geom_point(alpha = 0.4, aes(color = sample)) +
    theme_gray(base_size = 24) +
    scale_colour_manual(
      values = palette3,
      name = "",
      breaks = levels(samples$sample),
      labels = c("posterior", "prior")) +
    xlab(expression(beta)) +
    ylab(expression(b)) +
    theme(legend.position = "top", legend.direction = "horizontal")

  ggsave("van_allen_prior_posterior_scatter_lambda_beta_b.png")

  ggplot(samples, aes(x = lambda_alpha, y = lambda_b)) +
    geom_point(alpha = 0.5, aes(color = sample)) +
    scale_colour_manual(
      values = palette3,
      name = "",
      breaks = levels(samples$sample),
      labels = c("posterior", "prior")) +
    theme_gray(base_size = 24) +
    xlab(expression(alpha)) +
    ylab(expression(b)) +
    theme(legend.position = "top", legend.direction = "horizontal")

  ggsave("van_allen_prior_posterior_scatter_lambda_alpha_b.png")


  ggplot(prior_samples, aes(x = lambda_beta)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab(expression(beta))

  ggsave("van_allen_histogram_lambda_beta_prior.png")

  ggplot(posterior_samples, aes(x = lambda_beta)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab(expression(beta))

  ggsave("van_allen_histogram_lambda_beta_posterior.png")

  ggplot(samples, aes(x = lambda_beta)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    facet_grid(sample ~ .) +
    xlab(expression(beta))

  ggsave("van_allen_histogram_lambda_beta.png")

  ggplot(samples, aes(x = lambda_beta)) +
    geom_density(alpha = .2, aes(fill = sample)) + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab(expression(beta)) +
    scale_fill_manual(
       values = palette3,
       name = "",
       breaks = levels(samples$sample),
       labels = c("posterior", "prior")) +
    theme(legend.position = "top", legend.direction = "horizontal")

  ggsave("van_allen_density_lambda_beta.png")

  ggplot(prior_samples, aes(x = lambda_b)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab("b")

  ggsave("van_allen_histogram_lambda_b_prior.png")

  ggplot(posterior_samples, aes(x = lambda_b)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab("b")

  ggsave("van_allen_histogram_lambda_b_posterior.png")

  ggplot(samples, aes(x = lambda_b)) +
    geom_density(alpha = .2, aes(fill = sample)) + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab(expression(b)) +
    scale_fill_manual(
       values = c("#CC0C0099", "#5C88DA99"),
       name = "",
       breaks = levels(samples$sample),
       labels = c("posterior", "prior")) +
    theme(legend.position = "top", legend.direction = "horizontal")

  ggsave("van_allen_density_lambda_b.png")

  ggplot(samples, aes(x = lambda_b)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    facet_grid(sample ~ .) +
    xlab(expression(b))


  ggsave("van_allen_histogram_lambda_b.png")

  ggplot(prior_samples, aes(x = lambda_alpha)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab(expression(alpha))

  ggsave("van_allen_histogram_lambda_alpha_prior.png")

  ggplot(posterior_samples, aes(x = lambda_alpha)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab(expression(alpha))

  ggsave("van_allen_histogram_lambda_alpha_posterior.png")

  ggplot(samples, aes(x = lambda_alpha)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    facet_grid(sample ~ .) +
    xlab(expression(alpha))

  ggsave("van_allen_histogram_lambda_alpha.png")

  ggplot(samples, aes(x = lambda_alpha)) +
    geom_density(alpha = .2, aes(fill = sample)) + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab(expression(alpha)) +
    scale_fill_manual(
       values = c("#CC0C0099", "#5C88DA99"),
       name = "",
       breaks = levels(samples$sample),
       labels = c("posterior", "prior")) +
    theme(legend.position = "top", legend.direction = "horizontal")

  ggsave("van_allen_density_lambda_alpha.png")

} else {


  qplot(
    prior_samples$"Q_beta", prior_samples$"Q_b",
    xlab = expression(beta), ylab = expression(b))

  ggsave("van_allen_scatter_Q_beta_b_prior.png")

  qplot(
    posterior_samples$"Q_beta", posterior_samples$"Q_b",
    xlab = expression(beta), ylab = expression(b))
  ggsave("van_allen_scatter_Q_beta_b_posterior.png")

  qplot(
    prior_samples$"Q_beta", prior_samples$"Q_alpha",
    xlab = expression(beta), ylab = expression(alpha))
  ggsave("van_allen_scatter_Q_beta_alpha_prior.png")

  qplot(
    posterior_samples$"Q_beta", posterior_samples$"Q_alpha",
    xlab = expression(beta), ylab = expression(alpha))
  ggsave("van_allen_scatter_Q_beta_alpha_posterior.png")


  ggplot(samples, aes(x = Q_beta, y = Q_b)) +
    geom_point(alpha = 0.4, aes(color = sample)) +
    theme_gray(base_size = 24) +
    scale_colour_manual(
      values = palette3,
      name = "",
      breaks = levels(samples$sample),
      labels = c("posterior", "prior")) +
    xlab(expression(beta)) +
    ylab(expression(b)) +
    theme(legend.position = "top", legend.direction = "horizontal")

  ggsave("van_allen_prior_posterior_scatter_Q_beta_b.png")

  ggplot(samples, aes(x = Q_alpha, y = Q_b)) +
    geom_point(alpha = 0.5, aes(color = sample)) +
    scale_colour_manual(
      values = palette3,
      name = "",
      breaks = levels(samples$sample),
      labels = c("posterior", "prior")) +
    theme_gray(base_size = 24) +
    xlab(expression(alpha)) +
    ylab(expression(b)) +
    theme(legend.position = "top", legend.direction = "horizontal")

  ggsave("van_allen_prior_posterior_scatter_Q_alpha_b.png")


  ggplot(prior_samples, aes(x = Q_beta)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab(expression(beta))

  ggsave("van_allen_histogram_Q_beta_prior.png")

  ggplot(posterior_samples, aes(x = Q_beta)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab(expression(beta))

  ggsave("van_allen_histogram_Q_beta_posterior.png")

  ggplot(samples, aes(x = Q_beta)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    facet_grid(sample ~ .) +
    xlab(expression(beta))

  ggsave("van_allen_histogram_Q_beta.png")

  ggplot(samples, aes(x = Q_beta)) +
    geom_density(alpha = .2, aes(fill = sample)) + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab(expression(beta)) +
    scale_fill_manual(
       values = palette3,
       name = "",
       breaks = levels(samples$sample),
       labels = c("posterior", "prior")) +
    theme(legend.position = "top", legend.direction = "horizontal")

  ggsave("van_allen_density_Q_beta.png")

  ggplot(prior_samples, aes(x = Q_b)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab("b") +
  ggsave("van_allen_histogram_Q_b_prior.png")

  ggplot(posterior_samples, aes(x = Q_b)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab("b") +
  ggsave("van_allen_histogram_Q_b_posterior.png")

  ggplot(samples, aes(x = Q_b)) +
    geom_density(alpha = .2, aes(fill = sample)) + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab(expression(b)) +
    scale_fill_manual(
       values = c("#CC0C0099", "#5C88DA99"),
       name = "",
       breaks = levels(samples$sample),
       labels = c("posterior", "prior")) +
    theme(legend.position = "top", legend.direction = "horizontal")

  ggsave("van_allen_density_Q_b.png")

  ggplot(samples, aes(x = Q_b)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    facet_grid(sample ~ .) +
    xlab(expression(b)) +
  ggsave("van_allen_histogram_Q_b.png")

  ggplot(prior_samples, aes(x = Q_alpha)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab(expression(alpha)) +
  ggsave("van_allen_histogram_Q_alpha_prior.png")

  ggplot(posterior_samples, aes(x = Q_alpha)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab(expression(alpha)) +
  ggsave("van_allen_histogram_Q_alpha_posterior.png")

  ggplot(samples, aes(x = Q_alpha)) +
    geom_histogram(aes(y = ..density..), # Histogram with density instead of count on y-axis
                   binwidth = .5,
                   colour = "black", fill = "white") +
    geom_density(alpha = .2, fill = "#FF6666") + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    facet_grid(sample ~ .) +
    xlab(expression(alpha)) +
  ggsave("van_allen_histogram_Q_alpha.png")

  ggplot(samples, aes(x = Q_alpha)) +
    geom_density(alpha = .2, aes(fill = sample)) + # Overlay with transparent density plot
  theme_gray(base_size = 24) +
    xlab(expression(alpha)) +
    scale_fill_manual(
       values = c("#CC0C0099", "#5C88DA99"),
       name = "",
       breaks = levels(samples$sample),
       labels = c("posterior", "prior")) +
    theme(legend.position = "top", legend.direction = "horizontal")

  ggsave("van_allen_density_Q_alpha.png")

}


