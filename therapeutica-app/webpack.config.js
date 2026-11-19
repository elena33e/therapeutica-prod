const path = require('path');
const TerserPlugin = require('terser-webpack-plugin');
const CssMinimizerPlugin = require('css-minimizer-webpack-plugin');
const MiniCssExtractPlugin = require('mini-css-extract-plugin');
const WarningsToErrorsPlugin = require('warnings-to-errors-webpack-plugin');

module.exports = (env, argv) => ({
  entry: {
    bundle: './src/main/resources/js/app.js',
    raport: './src/main/resources/js/raport.js'
  },

  output: {
    path: path.resolve(__dirname, './target/classes/static'),
    filename: 'js/[name].js',
    publicPath: '/'
  },

  devtool: argv.mode === 'production' ? false : 'eval-source-map',

  optimization: {
    minimize: argv.mode === 'production',
    minimizer: [
      new TerserPlugin(),
      new CssMinimizerPlugin()
    ]
  },

  performance: {
    maxAssetSize: 512 * 1024,
    maxEntrypointSize: 768 * 1024
  },

  plugins: [
    new MiniCssExtractPlugin({
      filename: 'css/[name].css'
    }),
    new WarningsToErrorsPlugin()
  ],

  module: {
    rules: [
      {
        test: /\.js$/,
        exclude: /node_modules/,
        use: {
          loader: 'babel-loader',
          options: {
            presets: ['@babel/preset-env']
          }
        }
      },
      {
        test: /\.css$/,
        use: [
          MiniCssExtractPlugin.loader,
          {
            loader: 'css-loader',
            options: {
              importLoaders: 1
            }
          },
          {
            loader: 'postcss-loader',
            options: {
              postcssOptions: {
                plugins: [
                  require('@tailwindcss/postcss')
                ]
              }
            }
          }
        ]
      },
      {
        test: /\.(woff2?|ttf|eot|svg)$/i,
        type: 'asset/resource',
        generator: {
          filename: 'fonts/[name].[contenthash:8][ext]'
        }
      }
    ]
  },

  resolve: {
    modules: [
      path.resolve(__dirname, './src/main/resources'),
      'node_modules'
    ]
  },

  devServer: {
    port: 8081,
    compress: true,
    hot: true,
    static: false,
    watchFiles: [
      'src/main/resources/templates/**/*.html',
      'src/main/resources/js/**/*.js',
      'src/main/resources/css/**/*.css'
    ],
    proxy: [
      {
        context: '**',
        target: 'http://localhost:8080',
        secure: false,
        prependPath: false,
        headers: {
          'X-Devserver': '1'
        }
      }
    ]
  }
});